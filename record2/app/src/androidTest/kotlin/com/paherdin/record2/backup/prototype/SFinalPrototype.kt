package com.paherdin.record2.backup.prototype

import android.os.CancellationSignal
import android.system.Os
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.paherdin.record2.backup.*
import com.paherdin.record2.storage.RecordDatabase
import com.paherdin.record2.storage.RoomRestoreStore
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

internal class SFinalPrototype {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val args=InstrumentationRegistry.getArguments()
    private val token=args.getString("token")!!
    private val run=args.getString("runId")!!
    @Volatile private var phase="admit"
    private val root=File(context.noBackupFilesDir,"g0-s-final-$token")
    private val path=context.getDatabasePath("g0-s-final-$token.db")
    private val fixture=SFinalFixture(args.getString("rows")!!.toInt(),args.getString("profile")?:"normal",args.getString("large")=="true",args.getString("largeNote")=="true")
    private fun db()=Room.databaseBuilder(context,RecordDatabase::class.java,path.name).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build().also{it.openHelper.writableDatabase}
    private fun emit(o:JSONObject){val dir=File(context.noBackupFilesDir,"s-final-reports");dir.mkdirs();File(dir,"$run.json").writeText(o.put("pid",android.os.Process.myPid()).put("runId",run).toString());println("S-FINAL $o")}
    @Test fun monitorProbe(){
        val work=File(root,"probe");work.mkdirs();val m=SFinalMonitor(path,work,{"probe"}){error(it)}
        val f=File(work,"etilqs_probe");m.start()
        RandomAccessFile(f,"rw").use{o->repeat(32){o.write(ByteArray(65536))};assertTrue(f.delete());Thread.sleep(250)
            val result=m.stop();assertTrue(result.getJSONObject("phasePeaks").getJSONObject("probe").getLong("qBytes")>=2097152)
            assertTrue(result.getJSONArray("qFileNames").toString().contains("(deleted)"));emit(JSONObject().put("verdict","PASS").put("unlinkedFdVisible",true).put("metrics",result))
        };context.deleteDatabase(path.name);root.deleteRecursively()
    }
    @Test fun sortEdges(){
        val random=java.util.Random(42);val engine=SFinalEngine(root,{},{})
        val keys=(0..8192).map{SFinalEngine.Key(random.nextLong(),random.nextLong(),random.nextLong(),random.nextLong(),it.toLong())}
        fun uuid(a:Long,b:Long)=UUID(a,b).toString()
        for(s in LogicalSegment.entries){
            val independent=Comparator<SFinalEngine.Key>{x,y->
                val a:List<Any>;val b:List<Any>
                when(s){
                    LogicalSegment.TRANSACTION,LogicalSegment.NOTE,LogicalSegment.PROPOSAL,LogicalSegment.OPERATION,LogicalSegment.SESSION,LogicalSegment.RUN->{a=listOf(x.a,uuid(x.b,x.c),x.row);b=listOf(y.a,uuid(y.b,y.c),y.row)}
                    LogicalSegment.LINK->{a=listOf(uuid(x.a,x.b),uuid(x.c,x.d),x.row);b=listOf(uuid(y.a,y.b),uuid(y.c,y.d),y.row)}
                    LogicalSegment.MESSAGE,LogicalSegment.REFERENCE->{a=listOf(uuid(x.a,x.b),x.c,x.row);b=listOf(uuid(y.a,y.b),y.c,y.row)}
                    LogicalSegment.DRAFT->{a=listOf(x.a,x.row);b=listOf(y.a,y.row)}
                    else->{a=listOf(uuid(x.a,x.b),x.row);b=listOf(uuid(y.a,y.b),y.row)}
                };SFinalEngine.compareOrder(a,b)
            };assertEquals(s.name,keys.sortedWith(independent),keys.sortedWith(engine.comparator(s)))
        };emit(JSONObject().put("verdict","PASS").put("sortUnsignedUuidAndSignedTime",true))
    }
    @Test fun prepareFixture(){
        root.mkdirs();val d=db();try{
            // Test-only bulk insert uses the same system engine as Room, on a separate handle.
            val raw=android.database.sqlite.SQLiteDatabase.openDatabase(path.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            try{val meta=fixture.seed(raw);File(root,"fixture.json").writeText(meta.toString());raw.execSQL("UPDATE note SET body='checkpoint-placeholder' WHERE id=?",arrayOf(BackupTestDataFactory.NOTE_ACTIVE));raw.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use{it.moveToFirst()}}
            finally{raw.close()}
            if(args.getString("fragmented")=="true"){
                val writable=d.openHelper.writableDatabase
                writable.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
                writable.execSQL("INSERT INTO note VALUES('ffffffff-ffff-4fff-8fff-ffffffffffff',zeroblob(67108864),0,1,0,0,NULL)")
                writable.execSQL("DELETE FROM note WHERE id='ffffffff-ffff-4fff-8fff-ffffffffffff'")
                writable.query("PRAGMA wal_checkpoint(TRUNCATE)").use{it.moveToFirst()}
            }
            emit(JSONObject().put("prepared",true).put("rows",fixture.wanted).put("profile",fixture.profile))
        }finally{d.close()}
    }
    private fun rawSource()=SFinalEngine.protectedOpen(path)
    private fun sourceFacts():String=rawSource().use{raw->
        val digest=MessageDigest.getInstance("SHA-256")
        for(table in LogicalSegment.entries.map{SFinalFixture.table(it)}+listOf("record_data_state","ai_control")){
            val names=raw.rawQuery("PRAGMA table_info($table)",null).use{c->buildList{while(c.moveToNext())add(c.getString(1))}}
            raw.compileStatement("SELECT json_object(${names.joinToString(","){"'$it',$it"}}) FROM $table WHERE rowid=?").use{statement->raw.rawQuery("SELECT rowid FROM $table ORDER BY rowid",null).use{c->while(c.moveToNext()){statement.bindLong(1,c.getLong(0));digest.update(statement.simpleQueryForString().toByteArray())}}}
        };SFinalEngine.hex(digest.digest())
    }
    private fun hash(file:File)=file.inputStream().use{input->val d=MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};SFinalEngine.hex(d.digest())}
    private fun readBags(file:File):Map<LogicalSegment,RowBag> = SFinalEngine.protectedOpen(file).use{raw->LogicalSegment.entries.associateWith{s->RowBag().also{bag->raw.compileStatement(SFinalEngine.payload(s)).use{statement->raw.rawQuery("SELECT rowid FROM ${SFinalFixture.table(s)}"+(if(s==LogicalSegment.DRAFT)" WHERE state='EDITING'"else""),null).use{c->while(c.moveToNext()){statement.bindLong(1,c.getLong(0));bag.add(SegmentCodecs.decode(s,StrictJson.decodeObject(statement.simpleQueryForString().toByteArray(),BackupLimits.MAX_LINE_BYTES)))}}}}}}
    private fun assertLiveSource(step:Long){
        val expected=LogicalSegment.entries.associateWith{RowBag()};fixture.rows(1+step){s,row->expected.getValue(s).add(row)};val actual=readBags(path)
        for(s in LogicalSegment.entries)assertEquals("live ${s.name}",expected.getValue(s).json().toString(),actual.getValue(s).json().toString())
        rawSource().use{raw->
            val state=JSONObject(SFinalEngine.scalar(raw,"SELECT json_object('id',id,'dataGeneration',dataGeneration,'businessRevision',businessRevision,'processOwnerId',processOwnerId,'restoreReceipt',restoreReceipt) FROM record_data_state"))
            assertEquals(1,state.getInt("id"));assertEquals(BackupTestDataFactory.GENERATION,state.getString("dataGeneration"));assertEquals(1+step,state.getLong("businessRevision"));assertEquals(BackupTestDataFactory.OWNER,state.getString("processOwnerId"));assertTrue(state.isNull("restoreReceipt"))
            val control=JSONObject(SFinalEngine.scalar(raw,"SELECT json_object('id',id,'mode',mode,'selected',selectedReceiverIdentity,'grant',grantReceiverIdentity,'scope',grantScopeVersion,'disclosure',grantDisclosureVersion,'at',grantedAt,'epoch',authEpoch) FROM ai_control"))
            assertEquals(1,control.getInt("id"));assertEquals("FULL",control.getString("mode"));assertEquals("synthetic-excluded-control",control.getString("selected"));assertEquals("synthetic-excluded-control",control.getString("grant"));assertEquals(1,control.getInt("scope"));assertEquals(1,control.getInt("disclosure"));assertEquals(BackupTestDataFactory.FINISH,control.getLong("at"));assertEquals(1,control.getInt("epoch"))
        }
    }
    private fun mutate(d:RecordDatabase,step:Long)=runBlocking {d.withTransaction{
        val raw=d.openHelper.writableDatabase;val t=BackupTestDataFactory.FINISH
        raw.execSQL("UPDATE record_transaction SET amountMinor=?,version=?,updatedAt=? WHERE id=?",arrayOf<Any>(1234+step,1+step,t+step,BackupTestDataFactory.TRANSACTION_ID))
        raw.execSQL("UPDATE note SET body=?,occurredAt=?,version=?,updatedAt=? WHERE id=?",arrayOf<Any>("atomic-writer-$step 中文🙂",BackupTestDataFactory.START+step,2+step,t+step,BackupTestDataFactory.NOTE_ACTIVE))
        raw.execSQL("DELETE FROM transaction_note_link WHERE transactionId=? AND noteId=?",arrayOf(BackupTestDataFactory.TRANSACTION_ID,BackupTestDataFactory.NOTE_ACTIVE))
        if(step%2==0L)raw.execSQL("INSERT INTO transaction_note_link VALUES(?,?)",arrayOf(BackupTestDataFactory.TRANSACTION_ID,BackupTestDataFactory.NOTE_ACTIVE))
        if(step==1L)raw.execSQL("UPDATE agent_run SET state='INTERRUPTED',activeSlot=NULL,finishedAt=? WHERE id=?",arrayOf<Any>(t+1,BackupTestDataFactory.RUN_EDITABLE))
        if(step>1)raw.execSQL("UPDATE agent_run SET state='INTERRUPTED',activeSlot=NULL,finishedAt=? WHERE id=?",arrayOf<Any>(t+step,SFinalFixture.uid(22,step-1)))
        val session=SFinalFixture.uid(21,step);val r=SFinalFixture.uid(22,step)
        raw.execSQL("INSERT INTO agent_session VALUES(?,?,?,?,1,1)",arrayOf<Any>(session,"live-$step",t+step,t+step+1))
        raw.execSQL("INSERT INTO agent_run VALUES(?,?,?,?,?,1,?,0,1,'RUNNING',1,?,NULL,NULL)",arrayOf<Any>(r,session,BackupTestDataFactory.GENERATION,BackupTestDataFactory.OWNER,BackupTestDataFactory.RECEIVER,SFinalFixture.uid(23,step),t+step))
        raw.execSQL("INSERT INTO agent_message VALUES(?,?,?,0,'USER',?,?,'INPUT',NULL,NULL,NULL)",arrayOf<Any>(SFinalFixture.uid(24,step),session,r,"live-$step",t+step))
        raw.execSQL("UPDATE record_data_state SET businessRevision=? WHERE id=1",arrayOf(1+step))
    }}
    @Test fun verifyKilled(){
        val marker=JSONObject(File(root,"kill.json").readText());val d=db()
        try{
            assertEquals(marker.getString("facts"),sourceFacts());assertFalse(File(root,"success.json").exists())
            if(marker.getString("phase")=="copy")assertFalse("kill missed active copy",File(root,"copy-returned").exists())
            val task=File(marker.getString("task"));val zip=File(task,"logical.zip")
            if(zip.exists()&&marker.getString("phase")!="verify-read")assertTrue(runCatching{LogicalZipReader.verify(zip)}.isFailure)
            val sweep=BackupStaging.sweep(File(root,"tasks"));assertTrue(sweep.failed.isEmpty());assertFalse(task.exists())
            rawSource().use{assertEquals("ok",SFinalEngine.scalar(it,"PRAGMA integrity_check"))};mutate(d,1)
            emit(JSONObject().put("verdict","PASS").put("phase",marker.getString("phase")).put("source14Unchanged",true).put("orphanDeleted",true).put("writerAfterRestart",true).put("killedPid",marker.getInt("pid")))
        }finally{d.close();context.deleteDatabase(path.name);root.deleteRecursively()}
    }
    @Test fun runExperiment()=runBlocking {
        val d=db();val report=JSONObject().put("rowsRequested",fixture.wanted).put("profile",fixture.profile).put("large",fixture.large).put("largeNote",fixture.largeNote)
        d.openHelper.writableDatabase.execSQL("UPDATE note SET body='proposal note' WHERE id=?",arrayOf(BackupTestDataFactory.NOTE_ACTIVE))
        val fault=args.getString("fault")?:"none";val at=args.getString("at")?:"copy";val concurrent=args.getString("concurrent")=="true"
        val work=File(File(root,"tasks"),"export-${UUID.randomUUID()}");work.mkdirs();val target=File(work,"snapshot.db")
        val baselineFacts=if(fault!="none")sourceFacts()else"";val dbHash=hash(path);val wal=File(path.path+"-wal");val walHash=if(wal.exists())hash(wal)else""
        val signal=CancellationSignal();val stop=AtomicBoolean(false);val aborted=AtomicBoolean(false)
        var abortReason="";var writerSteps=0L;var writerError="";var maxLatency=0L;var copyCommits=0;val writeSamples=JSONArray()
        fun abort(reason:String){abortReason=reason;aborted.set(true);signal.cancel()}
        fun marker(point:String){File(root,"kill.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("phase",point).put("facts",baselineFacts).put("task",work.path).toString())}
        fun hook(point:String){
            phase=point
            if(fault=="cancel"&&point==at)throw java.util.concurrent.CancellationException("cancel-$point")
            if(fault=="kill"&&point==at){marker(point);while(true)Thread.sleep(50)}
        }
        val monitor=SFinalMonitor(path,work,{phase},{if(fault=="free-floor"&&phase=="copy"&&target.length()>0)67108863 else android.os.StatFs(work.path).availableBytes}){abort(it)}
        val observerDone=AtomicBoolean(false)
        val observer=Thread({var marked=false;while(!observerDone.get()){
            if(phase=="copy"&&target.length()>16384){
                if(fault=="cancel"&&at=="copy")abort("cancel-copy")
                if(fault=="kill"&&at=="copy"&&!marked){marked=true;marker("copy")}
            };Thread.sleep(1)
        }},"s-final-fault")
        val writer=Thread({while(!stop.get()){
            if((concurrent||fault=="wal")&&phase=="copy"&&target.length()>0||concurrent&&writerSteps>0){
                try{val inCopy=phase=="copy";val start=System.nanoTime();mutate(d,writerSteps+1);writerSteps++;val latency=SFinalEngine.ms(start);maxLatency=maxOf(maxLatency,latency);if(inCopy&&phase=="copy")copyCommits++;if(writeSamples.length()<100)writeSamples.put(JSONObject().put("step",writerSteps).put("latencyMs",latency).put("startedInCopy",inCopy).put("committedInCopy",phase=="copy"))}
                catch(e:Throwable){writerError=e.toString();break}
                if(fault=="wal")break
            };Thread.sleep(20)
        }},"s-final-writer")
        var zip:File?=null;var caught:Throwable?=null;val started=System.nanoTime()
        try{
            val pages=rawSource().use{val pageSize=SFinalEngine.scalar(it,"PRAGMA page_size").toLong();val total=SFinalEngine.scalar(it,"PRAGMA page_count").toLong();val free=SFinalEngine.scalar(it,"PRAGMA freelist_count").toLong();report.put("sourcePhysicalPageBytes",total*pageSize).put("sourceFreelistBytes",free*pageSize);(total-free)*pageSize}
            val cap=if(fault=="target-cap")16384L else pages+maxOf(1048576L,pages/10)
            val walWork=minOf(SFinalMonitor.BackupWalCap,maxOf(1048576L,pages/16))
            report.put("sourcePageBytes",pages).put("sourceCap",cap).put("walWorkCap",walWork).put("qWorkCap",1048576).put("copyAdmissionEstimate",cap+1048576+walWork+67108864).put("freeBefore",android.os.StatFs(work.path).availableBytes)
            require(android.os.StatFs(work.path).availableBytes>=cap+1048576+walWork+67108864)
            monitor.caps(cap,if(fault=="wal")1024 else walWork);monitor.start();observer.start();writer.start()
            phase="copy";val copyStart=System.nanoTime()
            report.put("captureStartedAtMs",System.currentTimeMillis())
            SFinalEngine.protectedOpen(path).use{source->
                report.put("systemSqliteVersion",SFinalEngine.scalar(source,"SELECT sqlite_version()"))
                    .put("systemSqliteSourceId",SFinalEngine.scalar(source,"SELECT sqlite_source_id()"))
                    .put("sourceReadOnly",source.isReadOnly).put("sourceJournalMode",SFinalEngine.scalar(source,"PRAGMA journal_mode"))
                assertTrue(source.isReadOnly);assertEquals("wal",SFinalEngine.scalar(source,"PRAGMA journal_mode"))
                monitor.sample()
                if(fault=="cancel"||fault=="kill")source.execSQL("PRAGMA cache_size=16")
                source.rawQuery("VACUUM main INTO ?",arrayOf(target.path),signal).use{assertEquals(0,it.count)}
            }
            File(root,"copy-returned").writeText("returned")
            report.put("captureFinishedAtMs",System.currentTimeMillis())
            report.put("copyMs",SFinalEngine.ms(copyStart)).put("snapshotBytes",target.length());phase="inspect";monitor.sample()
            if(aborted.get())throw IOException(abortReason)
            if(fault=="corrupt")RandomAccessFile(target,"rw").use{it.write(ByteArray(64){0x5a})}
            if(fault=="permission")Os.chmod(target.path,0)
            SFinalEngine.protectedOpen(target).use{snapshot->
                assertEquals("ok",SFinalEngine.scalar(snapshot,"PRAGMA integrity_check"));snapshot.rawQuery("PRAGMA foreign_key_check",null).use{assertEquals(0,it.count)}
                assertEquals("1",SFinalEngine.scalar(snapshot,"PRAGMA user_version"))
                assertEquals("c1b84c0a7822f354324484b6995cfb0d",SFinalEngine.scalar(snapshot,"SELECT identity_hash FROM room_master_table WHERE id=42"))
                val n=LogicalSegment.entries.sumOf{s->SFinalEngine.scalar(snapshot,"SELECT COUNT(*) FROM ${SFinalFixture.table(s)}"+(if(s==LogicalSegment.DRAFT)" WHERE state='EDITING'"else"")).toLong()}
                require(n<=BackupLimits.MAX_ROWS_TOTAL)
                val blocks=android.os.StatFs(work.path).blockSizeLong
                val kBound=88*n+(2*((n+4095)/4096)+48)*blocks
                val zipWork=minOf(BackupLimits.MAX_ZIP_BYTES,android.os.StatFs(work.path).availableBytes-67108864-kBound-65536)
                require(zipWork>0);report.put("keyWorkBound",kBound).put("zipWorkQuota",zipWork).put("fsBlockBytes",blocks)
                val engine=SFinalEngine(work,::hook){if(aborted.get())throw IOException(abortReason);if(android.os.StatFs(work.path).availableBytes<67174400)throw IOException("free-floor")}
                zip=engine.export(snapshot,report,if(fault=="zip-output")1024 else zipWork)
            }
            if(args.getString("saf")=="true"){
                val cleanup=work.listFiles().orEmpty().filter{it.name.startsWith("sort-")||it.name.startsWith("snapshot.db")}
                report.put("reclaimedBeforeSaf",cleanup.sumOf{it.length()});cleanup.forEach{assertTrue(it.delete())}
                phase="saf";val safStart=System.nanoTime();val resolver=context.contentResolver
                val uri=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply{
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"g0-s-final-$token.zip");put(android.provider.MediaStore.Downloads.MIME_TYPE,"application/zip");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/Record2G0Prototype")
                })?:error("synthetic document unavailable")
                try{val out=ContentResolverBackupTarget(resolver,uri);out.writeFrom(zip!!);assertEquals(report.getString("zipSha256"),out.readBackSha256());assertEquals(zip!!.length(),out.readBackBytes());report.put("safWriteCloseReadback",true).put("safMs",SFinalEngine.ms(safStart))}
                finally{assertEquals(1,resolver.delete(uri,null,null))}
            }
        }catch(e:Throwable){caught=e;report.put("error",e.toString()).put("abortReason",abortReason)}
        finally{
            stop.set(true);observerDone.set(true)
            if(writer.isAlive)writer.join(10000);if(observer.isAlive)observer.join(10000)
            report.put("exportMs",SFinalEngine.ms(started)).put("metrics",monitor.stop()).put("writerSteps",writerSteps).put("writerCopyCommits",copyCommits).put("writerMaxLatencyMs",maxLatency).put("writerError",writerError).put("writeSamples",writeSamples)
        }
        try{
            if(fault!="none"){
                assertNotNull("fault must interrupt",caught);assertFalse(File(root,"success.json").exists())
                if(fault!="wal"){assertEquals(baselineFacts,sourceFacts());assertEquals(dbHash,hash(path));if(walHash.isNotEmpty())assertEquals(walHash,hash(wal))}
                else {assertTrue(writerSteps>0);assertLiveSource(writerSteps);report.put("source14MatchesCommittedWriterModel",true)}
                assertTrue(writerError.isEmpty());rawSource().use{assertEquals("ok",SFinalEngine.scalar(it,"PRAGMA integrity_check"))};mutate(d,writerSteps+1)
                report.put("source14Unchanged",fault!="wal").put("writerAfterAbort",true)
            }else{
                caught?.let{throw it};assertTrue(writerError.isEmpty())
                if(!concurrent){assertEquals(dbHash,hash(path));if(walHash.isNotEmpty())assertEquals(walHash,hash(wal));report.put("sourceDbWalByteIdentical",true)}
                else{
                    assertLiveSource(writerSteps)
                    report.put("liveSourceIndependentModel",true)
                }
                val expected=LogicalSegment.entries.associateWith{RowBag()};fixture.rows(report.getLong("snapshotRevision")){s,row->expected.getValue(s).add(row)}
                for(s in LogicalSegment.entries)assertEquals(s.name,expected.getValue(s).json().toString(),report.getJSONObject("oracle").getJSONObject(s.name).toString())
                report.put("allFieldsOracle",true)
                val targetName="g0-s-final-restore-${UUID.randomUUID()}.db";val restored=Room.databaseBuilder(context,RecordDatabase::class.java,targetName).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build();restored.openHelper.writableDatabase
                val restoreStart=System.nanoTime()
                try{
                    val service=RestoreService(RoomRestoreStore(restored),context.getDatabasePath(targetName),File(work,"restore"),DataOperationGate())
                    val source=object:BackupSource{override val displayName="s-final.zip";override suspend fun copyTo(destination:File,maxBytes:Long):Long {zip!!.inputStream().use{i->destination.outputStream().use{o->i.copyTo(o)}};return destination.length()}}
                    val candidate=service.preflight(source);val result=service.restore(candidate)
                    assertEquals(report.getLong("rows"),result.importedRows)
                    val transformed=LogicalSegment.entries.associateWith{RowBag()}
                    fixture.rows(report.getLong("snapshotRevision")){s,row->transformed.getValue(s).add(when(row){
                        is RunRow->if(row.state=="RUNNING")row.copy(state="INTERRUPTED",activeSlot=null,finishedAt=maxOf(result.receipt.restoredAt,row.startedAt))else row
                        is ProposalRow->if(row.state in listOf("EDITABLE","LOCKED"))row.copy(state="NEEDS_REVIEW",activeSlot=null)else row
                        else->row
                    })}
                    SFinalEngine.protectedOpen(context.getDatabasePath(targetName)).use{raw->for(s in LogicalSegment.entries){
                        val bag=RowBag();raw.compileStatement(SFinalEngine.payload(s)).use{statement->raw.rawQuery("SELECT rowid FROM ${SFinalFixture.table(s)}",null).use{c->while(c.moveToNext()){statement.bindLong(1,c.getLong(0));bag.add(SegmentCodecs.decode(s,StrictJson.decodeObject(statement.simpleQueryForString().toByteArray(),BackupLimits.MAX_LINE_BYTES)))}}}
                        assertEquals("restored ${s.name}",transformed.getValue(s).json().toString(),bag.json().toString())
                    };assertEquals("OFF",SFinalEngine.scalar(raw,"SELECT mode FROM ai_control"));assertEquals("0",SFinalEngine.scalar(raw,"SELECT COUNT(*) FROM ai_control WHERE grantReceiverIdentity IS NOT NULL"))}
                    report.put("preflightAndRestore",true).put("restoredAllFields",true).put("restoreMs",SFinalEngine.ms(restoreStart))
                    File(root,"success.json").writeText("{\"complete\":true}")
                }finally{restored.close();context.deleteDatabase(targetName)}
            }
            if(target.exists())Os.chmod(target.path,384)
            assertTrue(work.deleteRecursively());report.put("cleanup",true).put("verdict","PASS");emit(report)
        }catch(e:Throwable){emit(report.put("verdict","FAIL").put("assertion",e.toString()));throw e}
        finally{d.close();context.deleteDatabase(path.name);root.deleteRecursively()}
    }
}
