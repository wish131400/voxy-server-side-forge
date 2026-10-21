package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class NextInvalidationTest {
    private static volatile long sink;

    @Test void historyOverflowAndConcurrentPublicationCannotMissMembers() throws Exception {
        var journal = new PredictionSubmissionChanges();
        var members = new LongOpenHashSet(new long[]{7});
        journal.publish(8); assertFalse(journal.affects(0,1,members));
        journal.publish(7); assertTrue(journal.affects(0,2,members));
        assertFalse(journal.affects(2,2,members));
        for(int i=0;i<PredictionSubmissionChanges.CAPACITY;i++)journal.publish(8);
        assertTrue(journal.affects(1,journal.epoch(),members),"overwritten history must validate exactly");
        long after=journal.epoch();
        var writer=new Thread(()->{for(int i=0;i<4096;i++)journal.publish(i==2000?7:8);});
        writer.start();writer.join();
        assertTrue(journal.affects(after,journal.epoch(),members));
        assertTrue(journal.affects(-1,journal.epoch(),members));
        long concurrentAfter=journal.epoch();
        var start=new java.util.concurrent.CountDownLatch(1);
        var concurrent=new Thread(()->{try{start.await();}catch(InterruptedException e){throw new AssertionError(e);}for(int i=0;i<PredictionSubmissionChanges.CAPACITY*4;i++)journal.publish(7);});
        concurrent.start();start.countDown();
        while(concurrent.isAlive()){
            long until=journal.epoch();if(until>concurrentAfter)assertTrue(journal.affects(concurrentAfter,until,members),"concurrent ring overwrite must not hide a member");
        }
        concurrent.join();assertTrue(journal.affects(concurrentAfter,journal.epoch(),members));
    }

    @Test void unrelatedEventsReusePassButRealGpuAndMorphEventsInvalidateImmediately() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var packed=PredictionPreparedPassLocalTest.mesh();
        var draws=ResidentRecordIntegrationTest.fixture(64,packed);
        var input=new PredictionGpuSelectionInput();var pass=new PredictionPreparedPassLocalTest.Pass(false);
        var camera=new Vec3(100,80,100);input.update(draws,camera,16384);pass.prepare(input.candidates(),1,camera);
        var candidates=input.candidates();long revision=pass.counter("revision");
        var unrelated=PredictionPreparedPassLocalTest.mesh();unrelated.trackSubmission();unrelated.morph(null,0,0);
        input.update(draws,camera,16384);pass.prepare(input.candidates(),1,camera);
        assertSame(candidates,input.candidates());assertEquals(revision,pass.counter("revision"));
        packed.morph(new float[]{0},0,100);input.update(draws,camera,16384);pass.prepare(input.candidates(),1,camera);
        assertTrue(pass.counter("revision")>revision);
        long oldRevision=pass.counter("revision");
        PredictionPreparedPassLocalTest.payload(draws.get(0).gpu(),null);changed(draws.get(0).gpu());
        input.update(draws,camera,16384);pass.prepare(input.candidates(),2,camera);
        assertEquals(63,input.candidates().size());assertNull(pass.ranges(draws.get(0)));
        assertTrue(pass.counter("revision")>oldRevision);
        var moved=new Vec3(100000,80,100000);input.update(draws,moved,32);assertTrue(input.candidates().isEmpty());
        input.clear();pass.clear.invoke(pass.value);
    }

    @Test void compareCurrentBaselineWithUnrelatedSparseAndBulkWork() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var rows=new ArrayList<String>();rows.add("tiles,case,round,mode,cpu_ns,allocated_bytes,input_ns,pass_ns");
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId();
        for(int count:new int[]{512,4096,8192})for(String scenario:List.of("stable","unrelated","unrelated-gpu","one-gpu","1pct-gpu","1pct-source","replace-all","move","reorder","remove")) {
            var shared=PredictionPreparedPassLocalTest.mesh();var draws=ResidentRecordIntegrationTest.fixture(count,shared);
            var old=new NextBeforeGpuSelectionInput();var next=new PredictionGpuSelectionInput();
            var oldPass=new NextBeforePass(false);var nextPass=new PredictionPreparedPassLocalTest.Pass(false);
            var unrelated=PredictionPreparedPassLocalTest.mesh();unrelated.trackSubmission();
            var unrelatedGpu=new PredictionGpuTile(draws.get(0).tile().key());
            var camera=new Vec3(3000,80,3000);List<PredictionRenderer.Draw> source=List.copyOf(draws);
            long[][] times=new long[2][129],bytes=new long[2][129];long residency=1;
            for(int round=-240;round<129;round++) {
                if(scenario.equals("unrelated"))unrelated.morph(null,0,0);
                if(scenario.equals("unrelated-gpu"))changed(unrelatedGpu);
                if(scenario.equals("one-gpu")){changed(draws.get(Math.floorMod(round,count)).gpu());residency++;}
                if(scenario.equals("1pct-gpu")){for(int i=0;i<Math.max(1,count/100);i++)changed(draws.get(Math.floorMod(round*37+i,count)).gpu());residency++;}
                if(scenario.equals("1pct-source")){
                    for(int i=0;i<Math.max(1,count/100);i++){int at=Math.floorMod(round*37+i,count);draws.set(at,ResidentRecordIntegrationTest.replacement(draws.get(at)));}
                    source=List.copyOf(draws);residency++;
                }
                if(scenario.equals("replace-all")){
                    for(int i=0;i<count;i++)draws.set(i,ResidentRecordIntegrationTest.replacement(draws.get(i)));
                    source=List.copyOf(draws);residency++;
                }
                if(scenario.equals("reorder")){Collections.rotate(draws,1);source=List.copyOf(draws);residency++;}
                if(scenario.equals("remove")){source=round%2==0?List.copyOf(draws.subList(1,draws.size())):List.copyOf(draws);residency++;}
                var view=scenario.equals("move")?camera.add(round*.17,0,0):camera;
                for(int order=0;order<2;order++) {
                    int mode=Math.floorMod(round+order,2);long allocated=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
                    if(mode==0)old.update(source,view,16384);else next.update(source,view,16384);
                    long updated=System.nanoTime();
                    if(mode==0){oldPass.prepare(old.candidates(),residency,view);sink=old.candidates().size();}
                    else{nextPass.prepare(next.candidates(),residency,view);sink=next.candidates().size();}
                    long elapsed=System.nanoTime()-start;allocated=bean.getThreadAllocatedBytes(thread)-allocated;
                    if(round>=0){times[mode][round]=elapsed;bytes[mode][round]=allocated;rows.add(count+","+scenario+","+round+","+mode+","+elapsed+","+allocated+","+(updated-start)+","+(elapsed-(updated-start)));}
                }
                assertEquals(old.candidates(),next.candidates());assertEquals(oldPass.quads(),nextPass.counter("selectedCandidates"));
            }
            System.out.printf(Locale.ROOT,"NEXT_INVALIDATION tiles=%d case=%s CPU_ms=%.6f->%.6f bytes=%d->%d%n",count,scenario,
                    CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,
                    CompletionGpuFixture.median(bytes[0]),CompletionGpuFixture.median(bytes[1]));
            oldPass.clear();nextPass.clear.invoke(nextPass.value);old.clear();next.clear();
        }
        CompletionGpuFixture.save("next-invalidation.csv",rows);
    }

    static void changed(PredictionGpuTile tile)throws Exception {
        var method=PredictionGpuTile.class.getDeclaredMethod("submissionChanged");method.setAccessible(true);method.invoke(tile);
    }
}
