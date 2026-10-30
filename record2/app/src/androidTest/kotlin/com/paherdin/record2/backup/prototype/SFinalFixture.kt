package com.paherdin.record2.backup.prototype

import android.database.sqlite.SQLiteDatabase
import com.paherdin.record2.backup.*
import com.paherdin.record2.proposals.*
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Test-only independent row model; never reads the snapshot to construct its expectations. */
internal class SFinalFixture(val wanted: Int, val profile: String, val large: Boolean, val largeNote:Boolean=false) {
    private val base = BackupTestDataFactory.create()
    private val t = BackupTestDataFactory.START
    private val end = BackupTestDataFactory.FINISH
    val heavy = profile == "heavy"
    private val unitSize = if (heavy) 34 else 31
    private val baseRows = LogicalSegment.entries.sumOf { base.content.rows(it).size }
    private val bigRows = if (large) 17 else 0
    private val units = if (large) 16 else (wanted - baseRows) / unitSize
    private val padding = wanted - baseRows - bigRows - units * unitSize
    init { require(padding >= 0) }

    fun rows(revision: Long = 1, emit: (LogicalSegment, SegmentRow) -> Unit) {
        val step = revision - 1
        for (s in LogicalSegment.entries) for (original in base.content.rows(s)) {
            val row = when {
                original is TransactionRow && step > 0 -> original.copy(amountMinor=1234+step,version=1+step,updatedAt=end+step)
                original is NoteRow && original.id == BackupTestDataFactory.NOTE_ACTIVE && step>0 -> original.copy(body="atomic-writer-$step 中文🙂",occurredAt=t+step,version=2+step,updatedAt=end+step)
                original is LinkRow && step%2==1L -> null
                original is RunRow && original.id==BackupTestDataFactory.RUN_EDITABLE && step>0 -> original.copy(state="INTERRUPTED",activeSlot=null,finishedAt=end+1)
                else -> original
            }
            if (row != null) emit(s,row)
        }
        val txCount=if(heavy)6 else 8
        repeat(units) { i ->
            val now=end+100+i
            val note0=uid(12,2L*i); val note1=uid(12,2L*i+1)
            repeat(2) { j -> emit(LogicalSegment.NOTE,NoteRow(uid(12,2L*i+j),if(largeNote&&i==0&&j==0)"x".repeat(7_000_000)else text(if(large)40 else if(heavy)4096 else if(profile=="small")256 else 1024,100000L+i*2+j),t+i,2,t,now,if(j==1&&i%7==0)now else null)) }
            repeat(txCount) { j -> emit(LogicalSegment.TRANSACTION,TransactionRow(uid(11,i.toLong()*txCount+j),"EXPENSE",100L+i+j,"CNY",base.content.accounts.single().id,base.content.categories.first{it.direction=="EXPENSE"}.id,text(if(heavy)160 else 80,i.toLong()*txCount+j),t+i,1,t,now)) }
            repeat(if(heavy)3 else 2){j->emit(LogicalSegment.LINK,LinkRow(uid(11,i.toLong()*txCount+j),if(j%2==0)note0 else note1))}
            val session=uid(13,i.toLong());emit(LogicalSegment.SESSION,SessionRow(session,"合成长期历史 $i",t,now+1,2,8))
            val payload=ProposalCodec.canonical(listOf(ProposalOperation.CreateNote("#n",text(if(large)1024 else if(heavy)7800 else if(profile=="small")1800 else 3800,i+900000L),ProposalTime(t,"UTC","2023-11-14T22:13:20",0))))
            val digest=ProposalCodec.digest(payload);val proposal=uid(16,i.toLong());val applied=i%2==0
            val frozen=CommitCodec.freeze(listOf(ProposalOperation.CreateNote("#n",text(if(large)1024 else if(heavy)7800 else if(profile=="small")1800 else 3800,i+900000L),ProposalTime(t,"UTC","2023-11-14T22:13:20",0))),digest){note0}
            repeat(4){j->
                val run=uid(14,i.toLong()*4+j);val req=uid(18,i.toLong()*4+j)
                emit(LogicalSegment.RUN,RunRow(run,session,base.generation,base.owner,BackupTestDataFactory.RECEIVER,1,req,2L*j,2,"COMPLETED",null,now,now+1,null))
                emit(LogicalSegment.MESSAGE,MessageRow(uid(15,i.toLong()*8+2*j),session,run,2L*j,"USER",text(if(heavy)320 else 160,i*4L+j+300000),now,"INPUT",null,null,null))
                val message=uid(15,i.toLong()*8+2*j+1)
                emit(LogicalSegment.MESSAGE,MessageRow(message,session,run,2L*j+1,"ASSISTANT",text(if(heavy)2048 else if(profile=="small")512 else 1024,i*4L+j+400000),now+1,if(j==0)"ProposalReady" else "Answer",null,if(j==0)proposal else null,if(j==0)1 else null))
                emit(LogicalSegment.REFERENCE,ReferenceRow(message,0,null,if(j%2==0)note0 else note1))
                if(heavy)emit(LogicalSegment.REFERENCE,ReferenceRow(message,1,uid(11,i.toLong()*txCount+j),null))
            }
            val origin=uid(14,i.toLong()*4)
            emit(LogicalSegment.PROPOSAL,ProposalRow(proposal,session,origin,origin,uid(18,i.toLong()*4),payload,1,payload,digest,1,base.generation,if(applied)"APPLIED" else "LOCKED",if(applied)null else 1,t,now))
            emit(LogicalSegment.OPERATION,OperationRow(uid(17,i.toLong()),proposal,1,base.generation,frozen.canonicalJson,frozen.payloadDigest,1,if(applied)"Applied" else "Pending",t,if(applied)now else null,if(applied)CommitResultCodec.canonical(AppliedCommitResult(1,listOf(AppliedOutcome.CreateNote(note0,1))))else null,null))
        }
        repeat(padding){i->emit(LogicalSegment.NOTE,NoteRow(uid(19,i.toLong()),text(if(large)40 else 256,i+700000L),t+i,1,t,end+padding,null))}
        if(large)repeat(17){i->emit(LogicalSegment.TRANSACTION,TransactionRow(uid(20,i.toLong()),"EXPENSE",100,"CNY",base.content.accounts.single().id,base.content.categories.first{it.direction=="EXPENSE"}.id,text(7_400_000,i+800000L),t,1,t,end))}
        for(k in 1..step){
            val session=uid(21,k);val run=uid(22,k)
            emit(LogicalSegment.SESSION,SessionRow(session,"live-$k",end+k,end+k+1,1,1))
            emit(LogicalSegment.RUN,RunRow(run,session,base.generation,base.owner,BackupTestDataFactory.RECEIVER,1,uid(23,k),0,1,if(k==step)"RUNNING"else"INTERRUPTED",if(k==step)1 else null,end+k,if(k==step)null else end+k+1,null))
            emit(LogicalSegment.MESSAGE,MessageRow(uid(24,k),session,run,0,"USER","live-$k",end+k,"INPUT",null,null,null))
        }
    }

    fun seed(db:SQLiteDatabase):JSONObject {
        val statements=LogicalSegment.entries.associateWith{s->db.compileStatement("INSERT INTO ${table(s)} (${s.fields.joinToString()}) VALUES (${s.fields.joinToString{ "?" }})")}
        val bags=LogicalSegment.entries.associateWith{RowBag()}
        db.beginTransaction()
        try {
            db.execSQL("PRAGMA defer_foreign_keys=ON")
            rows {s,row->
                val text=SegmentCodecs.encode(row);val json=JSONObject(text);val stmt=statements.getValue(s)
                stmt.clearBindings();s.fields.forEachIndexed{i,key->val v=json.get(key);when(v){JSONObject.NULL->stmt.bindNull(i+1);is Number->stmt.bindLong(i+1,v.toLong());else->stmt.bindString(i+1,v.toString())}}
                stmt.executeInsert();bags.getValue(s).add(row)
            }
            db.execSQL("INSERT INTO record_data_state VALUES(1,?,1,?,NULL)",arrayOf(base.generation,base.owner))
            // Deliberately excluded control information; synthetic, never a credential.
            db.execSQL("INSERT INTO ai_control VALUES(1,'FULL',?,?,1,1,?,1)",arrayOf<Any>("synthetic-excluded-control","synthetic-excluded-control",end))
            db.setTransactionSuccessful()
        } finally {db.endTransaction();statements.values.forEach{it.close()}}
        return JSONObject().also{o->bags.forEach{(s,b)->o.put(s.name,b.json())}}
    }

    companion object {
        fun uid(kind:Int,i:Long)="${kind.toString(16).padStart(8,'0')}-0000-4000-8000-${i.toString(16).padStart(12,'0')}"
        fun table(s:LogicalSegment)=when(s){LogicalSegment.DRAFT->"native_finance_draft";LogicalSegment.PROPOSAL->"change_proposal";LogicalSegment.OPERATION->"commit_operation";else->s.path.substringAfterLast('/').substringBefore('.')}
        fun text(n:Int,seed:Long):String {val r=java.util.Random(seed);return buildString(n){repeat(n){i->append(if(i%4==0)('a'.code+r.nextInt(26)).toChar() else if(i%199==0)'\n'else'x')}}}
    }
}

/** Count, XOR and 256-bit modular sum of SHA256(canonical full row). No retained payloads. */
internal class RowBag {
    var count=0L;private val xor=ByteArray(32);private var sum=BigInteger.ZERO
    fun add(row:SegmentRow){val d=MessageDigest.getInstance("SHA-256").digest(SegmentCodecs.encode(row).toByteArray());for(i in d.indices)xor[i]=(xor[i].toInt() xor d[i].toInt()).toByte();sum=sum.add(BigInteger(1,d));count++}
    fun json()=JSONObject().put("rows",count).put("xor",xor.joinToString(""){"%02x".format(it.toInt() and 255)}).put("sum256",sum.and(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)).toString(16).padStart(64,'0'))
}
