package com.paherdin.record2.backup

import android.database.sqlite.SQLiteDatabase
import java.io.*
import java.util.PriorityQueue
import java.util.UUID

/** Fixed 4096-key runs, fan-in16. No payloads or unbounded SQL sorter. */
internal class SnapshotKeys(private val work: File, private val check: () -> Unit) {
    data class Key(val a:Long,val b:Long,val c:Long,val d:Long,val row:Long)
    var keyFiles=0;var keyBytes=0L;var sortMaxObjects=0
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
    fun sort(db:SQLiteDatabase,s:LogicalSegment):File {
        val fields=when(s){LogicalSegment.ACCOUNT,LogicalSegment.CATEGORY->"id";LogicalSegment.TRANSACTION,LogicalSegment.NOTE->"occurredAt,id";LogicalSegment.LINK->"transactionId,noteId";LogicalSegment.DRAFT->"slot";LogicalSegment.PROPOSAL,LogicalSegment.OPERATION,LogicalSegment.SESSION->"createdAt,id";LogicalSegment.RUN->"startedAt,id";LogicalSegment.MESSAGE->"sessionId,sequence";LogicalSegment.REFERENCE->"messageId,ordinal"}
        val cmp=comparator(s);var runs=ArrayList<File>();val chunk=ArrayList<Key>(4096)
        fun flush(){if(chunk.isEmpty())return;chunk.sortWith(cmp);val f=keyFile();DataOutputStream(f.outputStream().buffered(4096)).use{o->chunk.forEach{writeKey(o,it)}};keyBytes+=f.length();runs+=f;chunk.clear()}
        db.rawQuery("SELECT rowid,$fields FROM ${ExportTables.name(s)}"+(if(s==LogicalSegment.DRAFT)" WHERE state='EDITING'"else"")+" ORDER BY rowid",null).use {c->while(c.moveToNext()){
            check();fun uuid(n:Int):UUID{val v=c.getString(n);if(!canonicalUuid(v))fail(BackupError.InvalidSource,"noncanonical snapshot key");return UUID.fromString(v)}
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
}
