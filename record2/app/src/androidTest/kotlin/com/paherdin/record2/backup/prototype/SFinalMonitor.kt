package com.paherdin.record2.backup.prototype

import android.os.Debug
import android.os.StatFs
import android.system.Os
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Directory + open-FD inode accounting, including unlinked SQLite temporary files. */
internal class SFinalMonitor(val source:File,val work:File,val phase:()->String,val available:()->Long={StatFs(work.path).availableBytes},val abort:(String)->Unit) {
    private val done=AtomicBoolean(false)
    private val peaks=LinkedHashMap<String,JSONObject>()
    private val names=linkedSetOf<String>()
    private val baselineWal=File(source.path+"-wal").length()
    private var baseline=0L;private var peakPss=0L;private var peakJava=0L;private var peakNative=0L;private var minFree=Long.MAX_VALUE
    private val thread=Thread({while(!done.get()){sample();Thread.sleep(5)}},"s-final-files")
    private val memoryThread=Thread({while(!done.get()){memory();Thread.sleep(100)}},"s-final-memory")
    private var sourceCap=Long.MAX_VALUE;private var walCap=BackupWalCap
    fun caps(s:Long,w:Long){sourceCap=s;walCap=w}
    fun start(){baseline=memory();thread.start();memoryThread.start()}
    private fun memory():Long {val info=Debug.MemoryInfo();Debug.getMemoryInfo(info);peakPss=maxOf(peakPss,info.totalPss.toLong());val r=Runtime.getRuntime();peakJava=maxOf(peakJava,r.totalMemory()-r.freeMemory());peakNative=maxOf(peakNative,Debug.getNativeHeapAllocatedSize());return info.totalPss.toLong()}
    @Synchronized fun sample(mem:Boolean=false){
        val p=phase();val peak=peaks.getOrPut(p){JSONObject()}
        fun max(k:String,v:Long){peak.put(k,maxOf(peak.optLong(k),v))}
        val target=File(work,"snapshot.db");var s=0L;var z=0L;var k=0L;var q=0L;var allocated=0L
        val seen=hashSetOf<String>()
        fun account(path:String,link:String){try{
            val st=Os.stat(path);val inode="${st.st_dev}:${st.st_ino}";if(!seen.add(inode))return
            val len=st.st_size;val blocks=st.st_blocks*512
            val name=link.removeSuffix(" (deleted)")
            when {
                name==target.path->{s+=len;allocated+=blocks}
                name==File(work,"logical.zip").path->{z+=len;allocated+=blocks}
                name.startsWith(work.path+"/sort-")->{k+=len;allocated+=blocks}
                name.startsWith(target.path+"-")||name.substringAfterLast('/').startsWith("etilqs_")->{q+=len;allocated+=blocks;names+=link.substringAfterLast('/')}
            }
        }catch(_:Exception){}}
        work.listFiles()?.filter{it.isFile}?.forEach{account(it.path,it.path)}
        File("/proc/self/fd").listFiles()?.forEach{fd->try{val l=Os.readlink(fd.path);if(l.startsWith(work.path)||l.contains("etilqs_"))account(fd.path,l)}catch(_:Exception){}}
        val w=maxOf(0,File(source.path+"-wal").length()-baselineWal)
        val free=available();minFree=minOf(minFree,free)
        max("snapshotBytes",s);max("zipBytes",z);max("keyBytes",k);max("qBytes",q);max("walGrowthBytes",w);max("taskAllocatedBytes",allocated);max("addedBytes",s+z+k+q+w)
        if(p=="copy"&&s>sourceCap)abort("snapshot-cap")
        if(p=="copy"&&q>1048576L)abort("q-cap")
        if(p=="copy"&&w>walCap)abort("wal-cap")
        if(free<67108864L)abort("free-floor")
    }
    fun stop():JSONObject{done.set(true);thread.join();memoryThread.join();sample();memory();return JSONObject().put("baselinePssKiB",baseline).put("peakPssKiB",peakPss).put("pssIncreaseKiB",peakPss-baseline).put("javaPeakBytes",peakJava).put("nativePeakBytes",peakNative).put("minFreeBytes",minFree).put("walBaselineBytes",baselineWal).put("phasePeaks",JSONObject(peaks as Map<*,*>)).put("qFileNames",org.json.JSONArray(names.toList())).put("fileSampleMs",5).put("memorySampleMs",100)}
    companion object {const val BackupWalCap=402653184L}
}
