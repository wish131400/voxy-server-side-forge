package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

class PredictionLoadingBenefitBenchmark {
    private static final com.sun.management.ThreadMXBean CPU =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long sink;
    private record Measurement(double ns, double allocated, double cpu) { }
    private final List<Map<String,Object>> results = new ArrayList<>();
    @Test void compareRealProductionPathsWithThePreservedBaseline() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        for (int size : new int[]{256,4096,16384}) residency(size);
        for (int size : new int[]{32,256,4096,16384}) selection(size);
        boundaries();
        for (int size : new int[]{256,4096}) loading(size);
        var output = Path.of(System.getProperty("vss.loadingBenefitResults", "build-loading-benefit-20261014/results"));
        Files.createDirectories(output);
        Files.writeString(output.resolve("loading-benefit-benchmark.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(results));
    }
    static PredictionTile at(PredictionTile base, int index, long revision) {
        return new PredictionTile(new PredictionTileKey(Level.OVERWORLD,index % 128,index / 128,0),
                base.heights(),base.groundHeights(),base.samples(),base.mesh(),base.depthBound(),0,revision,
                base.cellAxis(),base.spacingBlocks(),false);
    }
    private void residency(int size) {
        var base=PredictionLodSeamsTest.tile(0,0,1,64);
        var layout=VssLodLayout.of(65536,6,true,true);
        var tiles=new LinkedHashMap<PredictionTileKey,PredictionTile>();
        for(int i=0;i<size;i++) { var tile=at(base,i,1);tiles.put(tile.key(),tile); }
        var table=new PredictionTileTable<PredictionTile>(Level.OVERWORLD).changed(tiles,List.of());
        var initial=new PredictionTileManager.RenderSnapshot(Level.OVERWORLD,layout,table,Map.of(),Set.of());
        var snapshots=new ArrayList<PredictionTileManager.RenderSnapshot>();
        var updates=new ArrayList<List<PredictionTile>>();
        for(int frame=0;frame<160;frame++) {
            var changed=new LinkedHashMap<PredictionTileKey,PredictionTile>();
            for(int j=0;j<8;j++) { var tile=at(base,(frame*8+j)%size,frame+2);changed.put(tile.key(),tile); }
            table=table.changed(changed,List.of());
            snapshots.add(new PredictionTileManager.RenderSnapshot(Level.OVERWORLD,layout,table,Map.of(),Set.of()));
            updates.add(List.copyOf(changed.values()));
        }
        var old=new LoadingLegacyResidency();var next=new PredictionRenderResidency();
        old.retain(initial);next.retain(initial);
        for(var tile:tiles.values()) { old.uploaded(tile);next.uploaded(tile); }
        old.pendingUploads(initial);next.pendingUploads(initial);
        Runnable legacy=()-> {
            for(int f=0;f<snapshots.size();f++) { var s=snapshots.get(f);old.retain(s);sink+=old.pendingUploads(s).size();for(var t:updates.get(f))old.uploaded(t); }
        };
        Runnable modern=()-> {
            for(int f=0;f<snapshots.size();f++) { var s=snapshots.get(f);next.retain(s);sink+=next.pendingUploads(s).size();for(var t:updates.get(f))next.uploaded(t); }
        };
        compare("residency-eight-upgrades",size,snapshots.size(),legacy,modern);
        assertTrue(next.pendingUploads(snapshots.get(snapshots.size()-1)).isEmpty());
        assertEquals(old.snapshot(snapshots.get(snapshots.size()-1)).tiles(),next.snapshot(snapshots.get(snapshots.size()-1)).tiles());
    }
    private void selection(int size) {
        var random=new Random(73);var base=PredictionLodSeamsTest.tile(0,0,1,64);
        var layout=VssLodLayout.of(65536,6,true,true);
        var input=new ArrayList<PredictionTile>();for(int i=0;i<size;i++)input.add(at(base,random.nextInt(16384),i+1));
        var residentKeys=new HashSet<PredictionTileKey>();for(int i=0;i<input.size();i+=4)residentKeys.add(input.get(i).key());
        Comparator<PredictionTile> order=Comparator.comparingInt((PredictionTile tile)->residentKeys.contains(tile.key())?1:0)
                .thenComparingInt(tile->-tile.key().lod()).thenComparingInt(tile->PredictionWorkOrder.priority(
                        tile.key(),layout,PredictionWorkOrder.distanceSquared(tile.key(),layout,913,311),0,false,null))
                .thenComparingDouble(tile->PredictionWorkOrder.orderingDistance(tile.key(),layout,913,311,null));
        var expected=new ArrayList<>(input);expected.sort(order);
        var actual=PredictionUploadSelection.select(input,n->true,order,20);
        assertEquals(expected.subList(0,Math.min(expected.size(),actual.size())),actual);
        Runnable old=()-> { var visible=new ArrayList<PredictionTile>();for(var value:input)visible.add(value);
            var sorted=new ArrayList<>(visible);sorted.sort(order);sink+=sorted.get(0).revision(); };
        Runnable next=()-> { var selected=PredictionUploadSelection.select(input,n->true,order,20);sink+=selected.get(0).revision(); };
        compare("upload-prefix",size,1,old,next);
    }
    private void boundaries() {
        var base=PredictionLodSeamsTest.tile(0,0,1,64);
        for(int size:new int[]{256,4096,16384}) {
            var map=new HashMap<PredictionTileKey,PredictionSeamPreparation.Surface>();
            for(int i=0;i<size;i++) {
                var tile=at(base,i,1);map.put(tile.key(),new PredictionSeamPreparation.Surface(tile,new boolean[1],new boolean[1]));
            }
            var initial=new PredictionTileTable<PredictionSeamPreparation.Surface>(Level.OVERWORLD).changed(map,List.of());
            var changed=new ArrayList<>(map.keySet()).subList(0,8);
            Runnable old=()-> {
                var surfaces=new HashMap<PredictionTileKey,PredictionSeamPreparation.Surface>();
                map.forEach((key,value)->surfaces.put(key,new PredictionSeamPreparation.Surface(value.tile(),value.allowed(),value.features())));
                sink+=Map.copyOf(surfaces).size();
            };
            Runnable next=()->sink+=PredictionSeamPreparation.updateSurfaces(initial,changed,key-> {
                var value=map.get(key);return new PredictionSeamPreparation.Surface(value.tile(),new boolean[1],value.features());
            }).size();
            compare("boundary-input-eight-changes",size,1,old,next);
        }
    }
    private void loading(int size) throws Exception {
        boolean remember=dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain;
        dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain=false;
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                new net.minecraft.resources.ResourceLocation("minecraft","overworld"),42,-64,384,"noise","minecraft:overworld",1);
        try(var manager=new PredictionTileManager(Level.OVERWORLD,ClientTerrainSampler.custom(42,profile,(x,z)->64),
                new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime),null)) {
            var input=statusFixture(manager,size);
            var leaves=input.plan();
            var snapshot=PredictionTileManager.class.getDeclaredMethod("loadingInput",PredictionSchedulePreparation.Input.class);snapshot.setAccessible(true);
            var request=(PredictionLoadingPreparation.Input)snapshot.invoke(manager,input);
            var computed=PredictionLoadingPreparation.build(request);
            @SuppressWarnings("unchecked") var residentMap=(Map<PredictionTileKey,PredictionTile>)get(manager,"ready");
            var reference=new LoadingLegacyAccounting.Calculation(request,residentMap);
            reference.build();
            assertEquals(reference.loadingProgress,computed.progress());
            assertEquals(reference.foreground(),computed.foreground());
            Runnable old=reference::build;
            Runnable next=()-> {try {
                var value=(PredictionLoadingPreparation.Input)snapshot.invoke(manager,input);
                sink+=value.goals().size()+value.terrainLeaves().size();
            }catch(Exception failure){throw new AssertionError(failure);} };
            compare("loading-accounting-client-work",size,1,old,next);
            Runnable worker=()-> {for(int j=0;j<32;j++)sink+=PredictionLoadingPreparation.build(request).goals().size();};
            for(int j=0;j<16;j++)worker.run();
            var measures=new ArrayList<Measurement>();for(int j=0;j<21;j++)measures.add(measure(worker,32));
            results.add(Map.of("case","loading-accounting-worker-work","entries",size,"candidate",summary(measures)));
            var desired=PredictionTileManager.class.getDeclaredMethod("desiredSnapshot");desired.setAccessible(true);
            @SuppressWarnings("unchecked") var desiredKeys=(Set<PredictionTileKey>)get(manager,"desiredKeys");
            desiredKeys.addAll(leaves);
            Runnable oldDesired=()->sink+=Set.copyOf(desiredKeys).size();
            Runnable newDesired=()-> {try {sink+=((Set<?>)desired.invoke(manager)).size();}catch(Exception e){throw new AssertionError(e);} };
            compare("desired-snapshot-reuse",size,1,oldDesired,newDesired);
            var grace=Set.copyOf(leaves.subList(0,Math.min(256,leaves.size())));
            var queued=Set.copyOf(leaves.subList(0,Math.min(8,leaves.size())));
            Runnable oldCandidates=()-> {
                var candidates=new HashSet<>(input.residents().tiles().keySet());candidates.addAll(queued);candidates.addAll(grace);
                sink+=Set.copyOf(candidates).size();
            };
            Runnable newCandidates=()-> {
                var candidates=new HashSet<>(queued);candidates.addAll(grace);sink+=Set.copyOf(candidates).size();
            };
            compare("ownership-candidate-client-copy",size,1,oldCandidates,newCandidates);
            if (size == 256) {
                @SuppressWarnings("unchecked") var pending=(Set<PredictionTileKey>)get(manager,"pending");
                pending.addAll(leaves.subList(0,24));
                var subsets=new ArrayList<Set<PredictionTileKey>>();
                for(var name:List.of("cachePending","surfacePending","idlePending","scopePending","backgroundPending","refinementPending")) {
                    @SuppressWarnings("unchecked") var subset=(Set<PredictionTileKey>)get(manager,name);
                    subset.addAll(pending);subsets.add(subset);
                }
                var cleanup=PredictionTileManager.class.getDeclaredMethod("cleanupPending");cleanup.setAccessible(true);
                Runnable oldCleanup=()-> {for(int admission=0;admission<32;admission++)for(var subset:subsets)subset.retainAll(pending);};
                Runnable newCleanup=()-> {try {cleanup.invoke(manager);}catch(Exception e){throw new AssertionError(e);} };
                compare("pending-cleanup-32-admissions",24,1,oldCleanup,newCleanup);
            }
        } finally {dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain=remember;}
    }
    static PredictionSchedulePreparation.Input statusFixture(PredictionTileManager manager,int size) throws Exception {
        var base=PredictionLodSeamsTest.tile(0,0,1,64);var keys=new ArrayList<PredictionTileKey>();
        @SuppressWarnings("unchecked") var ready=(Map<PredictionTileKey,PredictionTile>)get(manager,"ready");
        var targets=new HashMap<PredictionTileKey,Integer>();var bands=new HashMap<PredictionTileKey,Integer>();
        for(int i=0;i<size;i++) {var tile=at(base,i,1);keys.add(tile.key());ready.put(tile.key(),tile);targets.put(tile.key(),64);bands.put(tile.key(),PredictionWorkView.VISIBLE);}
        @SuppressWarnings("unchecked") var captureEpochs=(Map<PredictionTileKey,Long>)get(manager,"captureEpochs");
        for(int i=0;i<size;i+=16)captureEpochs.put(keys.get(i),1L);
        var plan=List.copyOf(keys);var goals=Map.copyOf(targets);var view=PredictionWorkView.of(0,80,0,1,0,0,70,1);
        set(manager,"scheduledPlan",plan);set(manager,"scheduledLeaves",plan);set(manager,"ordinaryTargets",goals);set(manager,"terrainTargets",goals);
        set(manager,"workView",view);set(manager,"viewBands",Map.copyOf(bands));
        @SuppressWarnings("unchecked") var leaves=(Set<PredictionTileKey>)get(manager,"terrainLeaves");leaves.addAll(keys);
        var residents=manager.renderSnapshot();set(manager,"drawableSnapshot",residents);
        var axes=new ArrayList<Integer>();for(int l=0;l<manager.layout().levelCount();l++)axes.add(PredictionWorkOrder.initialCellAxis(l));
        return new PredictionSchedulePreparation.Input(manager.layout(),null,view,0,80,0,20,-64,384,0,plan,
                residents,Set.copyOf(keys),Set.of(),Set.of(),Set.of(),Set.of(),Set.of(),goals,goals,Map.of(),Map.of(),
                PredictionMediumCoverage.EMPTY,false,false,false,false,Map.copyOf(bands),Set.of(),Set.of(),
                List.copyOf(axes),null,Map.of(),Map.of(),Map.of(),System.nanoTime(),false);
    }
    static Object get(Object owner,String name)throws Exception{return PredictionPlanningTestSupport.get(owner,name);}
    static void set(Object owner,String name,Object value)throws Exception{PredictionPlanningTestSupport.set(owner,name,value);}

    private void compare(String name,int size,int operations,Runnable old,Runnable next) {
        int repeats = operations == 1 ? 32 : 1;
        Runnable oldBatch = () -> { for (int repeat=0;repeat<repeats;repeat++) old.run(); };
        Runnable nextBatch = () -> { for (int repeat=0;repeat<repeats;repeat++) next.run(); };
        for(int i=0;i<16;i++) { oldBatch.run();nextBatch.run(); }
        var before=new ArrayList<Measurement>();var after=new ArrayList<Measurement>();
        for(int i=0;i<21;i++) {
            if((i&1)==0) { before.add(measure(oldBatch,operations*repeats));after.add(measure(nextBatch,operations*repeats)); }
            else { after.add(measure(nextBatch,operations*repeats));before.add(measure(oldBatch,operations*repeats)); }
        }
        var a=summary(before);var b=summary(after);
        var result=new LinkedHashMap<String,Object>();result.put("case",name);result.put("entries",size);
        result.put("baseline",a);result.put("candidate",b);results.add(result);
        result.put("rounds",21);result.put("operationsPerMeasurement",operations*repeats);
        System.out.printf(Locale.ROOT,"LOADING_BENEFIT %s n=%d old=%.3f us new=%.3f us bytes=%.0f->%.0f cpu=%.3f->%.3f us%n",
                name,size,a.get("medianNs")/1000,b.get("medianNs")/1000,a.get("medianAllocatedBytes"),b.get("medianAllocatedBytes"),a.get("medianCpuNs")/1000,b.get("medianCpuNs")/1000);
    }
    private static Measurement measure(Runnable task,int operations) {
        long thread=Thread.currentThread().getId();
        long bytes=CPU.getThreadAllocatedBytes(thread),cpu=CPU.getCurrentThreadCpuTime(),start=System.nanoTime();
        task.run();
        return new Measurement((System.nanoTime()-start)/(double)operations,(CPU.getThreadAllocatedBytes(thread)-bytes)/(double)operations,
                (CPU.getCurrentThreadCpuTime()-cpu)/(double)operations);
    }
    private static Map<String,Double> summary(List<Measurement> samples) {
        double[] ns=samples.stream().mapToDouble(Measurement::ns).sorted().toArray();
        double[] allocation=samples.stream().mapToDouble(Measurement::allocated).sorted().toArray();
        double[] cpu=samples.stream().mapToDouble(Measurement::cpu).sorted().toArray();
        return Map.of("medianNs",ns[ns.length/2],"p95Ns",ns[(int)Math.ceil(ns.length*.95)-1],
                "medianAllocatedBytes",allocation[allocation.length/2],"medianCpuNs",cpu[cpu.length/2]);
    }
}
