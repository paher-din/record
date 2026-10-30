package com.paherdin.record2.backup.prototype

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.os.CancellationSignal
import com.paherdin.record2.backup.*
import java.io.*
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.UUID
import java.util.zip.*
import org.json.JSONObject

/** Isolated prototype only. Source/target use the same system engine; no Room driver change. */
internal class SFinalEngine(val work:File,val hook:(String)->Unit,val check:()->Unit) {
    data class Stat(val rows:Long,val bytes:Long,val compressed:Long,val crc:Long,val sha:String)
    data class Key(val a:Long,val b:Long,val c:Long,val d:Long,val row:Long)
    val timings=JSONObject();var keyFiles=0;var keyBytes=0L;var sortMaxObjects=0
    private var nextFile=0
    private fun keyFile()=File(work,"sort-${nextFile++}.keys").also{keyFiles++}
    fun comparator(s:LogicalSegment)=Comparator<Key>{x,y->
        fun u(a:Long,b:Long)=java.lang.Long.compareUnsigned(a,b)
        val c=when(s){
            LogicalSegment.TRANSACTION,LogicalSegment.NOTE,LogicalSegment.PROPOSAL,LogicalSegment.OPERATION,LogicalSegment.SESSION,LogicalSegment.RUN -> compareValues(x.a,y.a).takeIf{it!=0}?:u(x.b,y.b).takeIf{it!=0}?:u(x.c,y.c)
            LogicalSegment.LINK -> u(x.a,y.a).takeIf{it!=0}?:u(x.b,y.b).takeIf{it!=0}?:u(x.c,y.c).takeIf{it!=0}?:u(x.d,y.d)
            LogicalSegment.MESSAGE,LogicalSegment.REFERENCE -> u(x.a,y.a).takeIf{it!=0}?:u(x.b,y.b).takeIf{it!=0}?:compareValues(x.c,y.c)
            LogicalSegment.DRAFT -> compareValues(x.a,y.a)
            else -> u(x.a,y.a).takeIf{it!=0}?:u(x.b,y.b)
        };if(c==0)compareValues(x.row,y.row)else c
    }
    private fun writeKey(o:DataOutputStream,k:Key){o.writeLong(k.a);o.writeLong(k.b);o.writeLong(k.c);o.writeLong(k.d);o.writeLong(k.row)}
    private fun readKey(i:DataInputStream):Key?=try{Key(i.readLong(),i.readLong(),i.readLong(),i.readLong(),i.readLong())}catch(e:EOFException){null}

    /** Scan only fixed-width keys, fixed 4096-item runs, fan-in16. No SQL payload/global sort. */
    private fun sort(db:SQLiteDatabase,s:LogicalSegment):File {
        val fields=when(s){LogicalSegment.ACCOUNT,LogicalSegment.CATEGORY->"id";LogicalSegment.TRANSACTION,LogicalSegment.NOTE->"occurredAt,id";LogicalSegment.LINK->"transactionId,noteId";LogicalSegment.DRAFT->"slot";LogicalSegment.PROPOSAL,LogicalSegment.OPERATION,LogicalSegment.SESSION->"createdAt,id";LogicalSegment.RUN->"startedAt,id";LogicalSegment.MESSAGE->"sessionId,sequence";LogicalSegment.REFERENCE->"messageId,ordinal"}
        val cmp=comparator(s);var runs=ArrayList<File>();val chunk=ArrayList<Key>(4096)
        fun flush(){if(chunk.isEmpty())return;chunk.sortWith(cmp);val f=keyFile();DataOutputStream(f.outputStream().buffered(4096)).use{o->chunk.forEach{writeKey(o,it)}};keyBytes+=f.length();runs+=f;chunk.clear()}
        db.rawQuery("SELECT rowid,$fields FROM ${SFinalFixture.table(s)}"+(if(s==LogicalSegment.DRAFT)" WHERE state='EDITING'"else""),null).use {c->while(c.moveToNext()){
            check();fun uuid(n:Int):UUID{val v=c.getString(n);require(canonicalUuid(v));return UUID.fromString(v)}
            val k=when(s){
                LogicalSegment.DRAFT->Key(c.getLong(1),0,0,0,c.getLong(0))
                LogicalSegment.TRANSACTION,LogicalSegment.NOTE,LogicalSegment.PROPOSAL,LogicalSegment.OPERATION,LogicalSegment.SESSION,LogicalSegment.RUN->{val u=uuid(2);Key(c.getLong(1),u.mostSignificantBits,u.leastSignificantBits,0,c.getLong(0))}
                LogicalSegment.LINK->{val a=uuid(1);val b=uuid(2);Key(a.mostSignificantBits,a.leastSignificantBits,b.mostSignificantBits,b.leastSignificantBits,c.getLong(0))}
                LogicalSegment.MESSAGE,LogicalSegment.REFERENCE->{val u=uuid(1);Key(u.mostSignificantBits,u.leastSignificantBits,c.getLong(2),0,c.getLong(0))}
                else->{val u=uuid(1);Key(u.mostSignificantBits,u.leastSignificantBits,0,0,c.getLong(0))}
            };chunk+=k;sortMaxObjects=maxOf(sortMaxObjects,chunk.size);if(chunk.size==4096)flush()
        }};flush()
        while(runs.size>16){val merged=ArrayList<File>();for(group in runs.chunked(16)){val f=keyFile();merge(group,cmp,f,false);keyBytes+=f.length();merged+=f};runs=merged}
        val result=keyFile();merge(runs,cmp,result,true);keyBytes+=result.length();return result
    }
    private fun merge(files:List<File>,cmp:Comparator<Key>,output:File,idsOnly:Boolean){
        val ins=files.map{DataInputStream(it.inputStream().buffered(4096))};val heap=PriorityQueue<Pair<Key,Int>>{x,y->cmp.compare(x.first,y.first)}
        try{ins.forEachIndexed{i,input->readKey(input)?.let{heap.add(it to i)}};DataOutputStream(output.outputStream().buffered(4096)).use{o->while(heap.isNotEmpty()){check();val(k,i)=heap.remove();if(idsOnly)o.writeLong(k.row)else writeKey(o,k);readKey(ins[i])?.let{heap.add(it to i)}}}}finally{ins.forEach{it.close()}}
    }
    fun export(db:SQLiteDatabase,report:JSONObject,quota:Long=BackupLimits.MAX_ZIP_BYTES):File {
        val start=System.nanoTime();hook("sort")
        val keys=LogicalSegment.entries.associateWith{sort(db,it)}
        val count=keys.values.sumOf{it.length()/8};require(count<=BackupLimits.MAX_ROWS_TOTAL)
        report.put("keyFiles",keyFiles).put("keyBytes",keyBytes).put("sortMaxObjects",sortMaxObjects).put("rows",count)
        timings.put("sortMs",ms(start));val zip=File(work,"logical.zip")
        val gen=scalar(db,"SELECT dataGeneration FROM record_data_state WHERE id=1");val rev=scalar(db,"SELECT businessRevision FROM record_data_state WHERE id=1").toLong()
        report.put("snapshotRevision",rev)
        val bags=LogicalSegment.entries.associateWith{RowBag()};var stored=emptyMap<LogicalSegment,Stat>()
        repeat(2){pass->
            val stats=LinkedHashMap<LogicalSegment,Stat>();var total=0L;var rowTotal=0L
            hook("extract")
            ZipOutputStream(object:FilterOutputStream(zip.outputStream().buffered(65536)){
                var bytes=0L
                override fun write(b:ByteArray,off:Int,len:Int){check();if(bytes+len>quota)throw IOException("ZIP output work quota");out.write(b,off,len);bytes+=len}
                override fun write(b:Int){write(byteArrayOf(b.toByte()),0,1)}
            }).use {stream->
                for(s in LogicalSegment.entries){
                    val prior=stored[s];val n=keys.getValue(s).length()/8;val method=if(n==0L||prior!=null)0 else 8
                    val e=ZipEntry(s.path).apply{time=946771200000L;this.method=method;if(method==0){size=prior?.bytes?:0;compressedSize=size;crc=prior?.crc?:0}}
                    stream.putNextEntry(e);val hash=MessageDigest.getInstance("SHA-256");val crc=CRC32();var bytes=0L;var rows=0L
                    db.compileStatement(payload(s)).use{statement->DataInputStream(keys.getValue(s).inputStream().buffered(65536)).use{input->repeat(n.toInt()){
                        check();statement.bindLong(1,input.readLong());val raw=statement.simpleQueryForString()
                        // SQLite JSON quotes each field in the frozen order. The original strict
                        // reader/validator below checks every decoded row before any delivery.
                        // Avoid decoding/re-encoding twice solely to collect the test oracle.
                        val line=(raw+"\n").toByteArray();require(line.size-1<=BackupLimits.MAX_LINE_BYTES)
                        if(rowTotal==50L)hook("extract-row")
                        stream.write(line);if(rowTotal==50L){stream.flush();hook("zip")}
                        hash.update(line);crc.update(line);bytes+=line.size;rows++;rowTotal++;total+=line.size
                        require(bytes<=BackupLimits.MAX_ENTRY_UNCOMPRESSED&&total<=BackupLimits.MAX_TOTAL_UNCOMPRESSED)
                    }}}
                    stream.closeEntry();require(e.size==bytes&&e.crc==crc.value)
                    stats[s]=Stat(rows,bytes,e.compressedSize,crc.value,hex(hash.digest()))
                }
                val p=BackupManifest(BackupLimits.FORMAT,1,1,report.getLong("captureStartedAtMs"),gen,rev,1,"s-final-prototype",13,rowTotal,total,LogicalSegment.entries.map{s->stats.getValue(s).let{ManifestSegment(s.path,it.rows,it.bytes,it.sha)}},"0".repeat(64))
                val m=p.copy(contentDigest=ManifestCodec.contentDigest(p));val data=ManifestCodec.encode(m)
                stream.putNextEntry(ZipEntry("manifest.json").apply{time=946771200000L;method=0;size=data.size.toLong();compressedSize=size;crc=CRC32().apply{update(data)}.value});stream.write(data);stream.closeEntry()
                report.put("manifestDigest",m.contentDigest)
            }
            val bad=stats.filterValues{it.bytes>0&&it.compressed*100<it.bytes}
            if(bad.isEmpty()){
                report.put("passes",pass+1).put("logicalBytes",total).put("zipBytes",zip.length())
                timings.put("zipThroughMs",ms(start));hook("verify")
                var visited=false
                val priorOrder=HashMap<LogicalSegment,List<Any>>()
                val validator=StreamingBackupValidator(System.currentTimeMillis())
                val verified=LogicalZipReader.forEachRow(zip,cancellationCheck={check();if(!visited){visited=true;hook("verify-read")}}){s,row->
                    val key=order(row);priorOrder[s]?.let{old->require(compareOrder(old,key)<=0){"logical order $s"}};priorOrder[s]=key
                    validator.accept(s,row);bags.getValue(s).add(row)
                }
                validator.finish{check()}
                report.put("oracle",JSONObject().also{o->bags.forEach{(s,b)->o.put(s.name,b.json())}})
                report.put("zipSha256",verified.zipSha256);timings.put("verifiedThroughMs",ms(start));report.put("phases",timings)
                return zip
            }
            check(pass==0);stored=bad
        };error("unreachable")
    }
    companion object {
        fun protectedOpen(path:File)=SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY){throw SQLiteDatabaseCorruptException("source/target auto-deletion disabled")}
        fun scalar(db:SQLiteDatabase,sql:String)=db.compileStatement(sql).use{it.simpleQueryForString()}
        fun payload(s:LogicalSegment)="SELECT json_object(${s.fields.joinToString(","){"'$it',$it"}}) FROM ${SFinalFixture.table(s)} WHERE rowid=?"
        fun order(row:SegmentRow):List<Any> = when(row){
            is AccountRow->listOf(row.id);is CategoryRow->listOf(row.id)
            is TransactionRow->listOf(row.occurredAt,row.id);is NoteRow->listOf(row.occurredAt,row.id)
            is LinkRow->listOf(row.transactionId,row.noteId);is DraftRow->listOf(row.slot)
            is ProposalRow->listOf(row.createdAt,row.id);is OperationRow->listOf(row.createdAt,row.id)
            is SessionRow->listOf(row.createdAt,row.id);is RunRow->listOf(row.startedAt,row.id)
            is MessageRow->listOf(row.sessionId,row.sequence);is ReferenceRow->listOf(row.messageId,row.ordinal)
        }
        fun compareOrder(a:List<Any>,b:List<Any>):Int {for(i in a.indices){val c=if(a[i] is Number)(a[i]as Number).toLong().compareTo((b[i]as Number).toLong())else(a[i]as String).compareTo(b[i]as String);if(c!=0)return c};return 0}
        fun ms(start:Long)=(System.nanoTime()-start)/1000000
        fun hex(b:ByteArray)=b.joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
