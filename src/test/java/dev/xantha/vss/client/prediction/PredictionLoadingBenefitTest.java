package dev.xantha.vss.client.prediction;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
class PredictionLoadingBenefitTest {
    @BeforeAll static void bootstrap(){ ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void incrementalResidencyMatchesLegacyAcrossReplacementEvictionAndWithdrawal() {
        var base=PredictionLodSeamsTest.tile(0,0,1,64);
        var old=new LoadingLegacyResidency();var next=new PredictionRenderResidency();
        var data=new HashMap<PredictionTileKey,PredictionTile>();var random=new Random(42);
        var layout=VssLodLayout.of(4096,6,true,true);
        for(int f=0;f<160;f++) {
            int key=random.nextInt(40);
            var tile=PredictionLoadingBenefitBenchmark.at(base,key,f+1);
            if(f%7==0)data.remove(tile.key());else data.put(tile.key(),tile);
            var source=new RenderSnapshot(Level.OVERWORLD,layout,data,Map.of());
            old.retain(source);next.retain(source);
            assertEquals(new HashSet<>(old.pendingUploads(source)),new HashSet<>(next.pendingUploads(source)));
            if(!data.isEmpty()) {
                var upload=data.values().iterator().next();old.uploaded(upload);next.uploaded(upload);
                if(f%5==0){ old.evict(upload.key());next.evict(upload.key()); }
            }
            assertEquals(new HashSet<>(old.pendingUploads(source)),new HashSet<>(next.pendingUploads(source)));
            assertEquals(old.snapshot(source).tiles(),next.snapshot(source).tiles());
        }
        next.clear();assertTrue(next.pendingUploads(new RenderSnapshot(Level.OVERWORLD,layout,Map.of(),Map.of())).isEmpty());
    }
    @Test void uploadPrefixPreservesTiesFilteringAndPriorityAtSmallAndLargeBacklogs() {
        for(int n:new int[]{0,1,32,64,65,5000}) {
            var input=new ArrayList<Integer>();for(int i=0;i<n;i++)input.add(i);
            Comparator<Integer> order=Comparator.comparingInt(i->i%7);
            var expected=new ArrayList<Integer>();for(var i:input)if(i%3!=0)expected.add(i);expected.sort(order);
            var selected=PredictionUploadSelection.select(input,i->i%3!=0,order,20);
            assertEquals(expected.subList(0,Math.min(expected.size(),20)),selected);
            assertTrue(PredictionUploadSelection.select(input,i->true,order,0).isEmpty());
        }
    }
    @Test void largeResidentPageDeltasRequeueEvictionsWithoutAWorkerPublication() throws Exception {
        var base=PredictionLodSeamsTest.tile(0,0,1,64);
        var layout=VssLodLayout.of(65536,6,true,true);
        var data=new HashMap<PredictionTileKey,PredictionTile>();
        for(int i=0;i<1024;i++) {var tile=PredictionLoadingBenefitBenchmark.at(base,i,1);data.put(tile.key(),tile);}
        var table=new PredictionTileTable<PredictionTile>(Level.OVERWORLD).changed(data,List.of());
        var source=new RenderSnapshot(Level.OVERWORLD,layout,table,Map.of(),Set.of());
        var old=new LoadingLegacyResidency();var next=new PredictionRenderResidency();
        old.retain(source);next.retain(source);old.pendingUploads(source);next.pendingUploads(source);
        for(var tile:data.values()){old.uploaded(tile);next.uploaded(tile);}
        assertTrue(next.pendingUploads(source).isEmpty());
        var evicted=data.values().iterator().next();old.evict(evicted.key());next.evict(evicted.key());
        assertEquals(Set.of(evicted),new HashSet<>(next.pendingUploads(source)));
        assertEquals(new HashSet<>(old.pendingUploads(source)),new HashSet<>(next.pendingUploads(source)));
        var replacement=PredictionLoadingBenefitBenchmark.at(base,12,2);
        var removed=PredictionLoadingBenefitBenchmark.at(base,13,1).key();
        table=table.changed(Map.of(replacement.key(),replacement),List.of(removed));
        var changed=new RenderSnapshot(Level.OVERWORLD,layout,table,Map.of(),Set.of());
        old.retain(changed);next.retain(changed);
        assertEquals(old.snapshot(changed).tiles(),next.snapshot(changed).tiles());
        assertEquals(new HashSet<>(old.pendingUploads(changed)),new HashSet<>(next.pendingUploads(changed)));
        assertNull(PredictionLoadingBenefitBenchmark.get(next,"deltaFrom"),"consumed diffs must release the previous mesh snapshot");
        assertNull(PredictionLoadingBenefitBenchmark.get(next,"deltaTo"));
    }
    @Test void preparationTelemetryKeepsTheExistingUploadThroughputBudget() {
        var budget=new PredictionUploadBudget();budget.recordPreparation(PredictionUploadBudget.MAX_NANOS);
        assertTrue(budget.allows(1));budget.record(1,1);assertTrue(budget.allows(1));
        assertTrue(budget.diagnostics().contains("uploadPreparationUs=2000"));
        budget.reset();assertTrue(budget.diagnostics().contains("uploadPreparationUs=0"));
        budget.record(1,PredictionUploadBudget.MAX_NANOS);assertFalse(budget.allows(1));
    }
    @Test void discardedBoundaryResultCannotLoseChangesComparedWithThePublishedFrame() {
        var jobs=new ArrayDeque<Runnable>();var preparation=new PredictionSeamPreparation(jobs::add);
        var a=PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(0,0,1,64));
        var b=PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(1,0,1,80));
        var surfaces=new HashMap<PredictionTileKey,PredictionSeamPreparation.Surface>();
        for(var v:List.of(a,b))surfaces.put(v.tile().key(),new PredictionSeamPreparation.Surface(v.tile(),v.allowed(),v.allowed()));
        var table=new PredictionTileTable<PredictionSeamPreparation.Surface>(Level.OVERWORLD).changed(surfaces,List.of());
        preparation.request(1,table,List.of());jobs.removeFirst().run();var first=preparation.poll(1);assertNotNull(first);
        var changed=new boolean[b.allowed().length];Arrays.fill(changed,true);changed[0]=false;
        var replacement=new PredictionSeamPreparation.Surface(b.tile(),changed,changed);
        table=table.changed(Map.of(b.tile().key(),replacement),List.of());
        preparation.request(2,table,List.of());jobs.removeFirst().run();assertNull(preparation.poll(3));
        preparation.request(3,table,List.of());jobs.removeFirst().run();var result=preparation.poll(3);
        assertNotNull(result);assertTrue(result.maskChanges().containsKey(b.tile().key()));
        preparation.request(4,table,List.of());jobs.removeFirst().run();var stable=preparation.poll(4);
        assertTrue(stable.maskChanges().isEmpty());assertTrue(stable.lodChanges().patches().isEmpty());
    }
    @Test void backgroundLoadingAccountingMatchesTheSynchronousPolicyAndRejectsStalePublication() throws Exception {
        boolean remember=dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain;
        dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain=false;
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                new net.minecraft.resources.ResourceLocation("minecraft","overworld"),42,-64,384,"noise","minecraft:overworld",1);
        try(var manager=new PredictionTileManager(Level.OVERWORLD,ClientTerrainSampler.custom(42,profile,(x,z)->64),
                new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime),null)) {
            var schedule=PredictionLoadingBenefitBenchmark.statusFixture(manager,80);
            var snapshot=PredictionTileManager.class.getDeclaredMethod("loadingInput",PredictionSchedulePreparation.Input.class);snapshot.setAccessible(true);
            var request=(PredictionLoadingPreparation.Input)snapshot.invoke(manager,schedule);
            var expected=PredictionLoadingPreparation.build(request);
            var stableRequest=new PredictionLoadingPreparation.Input(schedule,request.leaves(),request.terrainLeaves(),request.owned(),
                    request.drawable(),request.captureEpochs(),expected.goals(),request.surfaceRadius(),request.visibility(),request.limits(),request.settings(),request.stateVersion());
            assertSame(expected.goals(),PredictionLoadingPreparation.build(stableRequest).goals(),
                    "unchanged progress must reuse the immutable display goals");
            var limitedKey=schedule.plan().get(0);
            var absent=new PredictionTileTable<PredictionTile>(Level.OVERWORLD)
                    .changed(schedule.residents().tiles(),List.of()).changed(Map.of(),List.of(limitedKey));
            var changedSchedule=new PredictionSchedulePreparation.Input(schedule.layout(),schedule.focus(),schedule.view(),
                    schedule.x(),schedule.y(),schedule.z(),schedule.pixels(),schedule.minY(),schedule.maxY(),schedule.revision(),
                    schedule.plan(),new RenderSnapshot(Level.OVERWORLD,schedule.layout(),absent,Map.of(),Set.of()),
                    schedule.desired(),schedule.surfaceDesired(),schedule.surfaceReady(),schedule.dirty(),schedule.fallback(),schedule.pending(),
                    schedule.ordinary(),schedule.terrain(),schedule.transitions(),schedule.idle(),schedule.medium(),schedule.mediumPending(),
                    schedule.previewPending(),schedule.localTurn(),schedule.moving(),schedule.bands(),schedule.dependencies(),schedule.hidden(),
                    schedule.initialAxes(),schedule.disk(),schedule.cacheMiss(),schedule.failed(),schedule.deferred(),schedule.now(),schedule.reclaimNeeded());
            var withoutLimit=new PredictionLoadingPreparation.Input(changedSchedule,request.leaves(),request.terrainLeaves(),request.owned(),
                    request.drawable(),request.captureEpochs(),request.goals(),request.surfaceRadius(),request.visibility(),Map.of(),request.settings(),request.stateVersion());
            assertTrue(PredictionLoadingPreparation.build(withoutLimit).foreground());
            var withLimit=new PredictionLoadingPreparation.Input(changedSchedule,request.leaves(),request.terrainLeaves(),request.owned(),
                    request.drawable(),request.captureEpochs(),request.goals(),request.surfaceRadius(),request.visibility(),
                    Map.of(limitedKey,new PredictionMeshFailure(schedule.revision(),request.captureEpochs().getOrDefault(limitedKey,0L),
                            changedSchedule.target(limitedKey),request.settings())),request.settings(),request.stateVersion());
            assertFalse(PredictionLoadingPreparation.build(withLimit).foreground(),"deterministic mesh limits cannot hold foreground work forever");
            var reference = new LoadingLegacyAccounting.Calculation(request, schedule.residents().tiles());
            reference.build(); assertEquals(reference.loadingProgress, expected.progress());
            var desired=PredictionTileManager.class.getDeclaredMethod("desiredSnapshot");desired.setAccessible(true);
            @SuppressWarnings("unchecked") var desiredKeys=(Set<PredictionTileKey>)PredictionLoadingBenefitBenchmark.get(manager,"desiredKeys");
            desiredKeys.addAll(schedule.plan());
            @SuppressWarnings("unchecked") var original=(Set<PredictionTileKey>)desired.invoke(manager);
            assertSame(original,desired.invoke(manager));
            var withdrawn=schedule.plan().get(0);var fallback=new PredictionTileKey(Level.OVERWORLD,-101,-92,1);
            desiredKeys.remove(withdrawn);desiredKeys.add(fallback);
            long version=(long)PredictionLoadingBenefitBenchmark.get(manager,"desiredVersion");
            PredictionLoadingBenefitBenchmark.set(manager,"desiredVersion",version+1);
            @SuppressWarnings("unchecked") var replaced=(Set<PredictionTileKey>)desired.invoke(manager);
            assertEquals(original.size(),replaced.size());assertFalse(replaced.contains(withdrawn));assertTrue(replaced.contains(fallback));
            assertTrue(original.contains(withdrawn),"a queued worker keeps an immutable earlier demand snapshot");
            var jobs=new ArrayDeque<Runnable>();
            var preparation=new PredictionClientPreparation<PredictionLoadingPreparation.Input,PredictionLoadingPreparation.Result>(jobs::add,PredictionLoadingPreparation::build);
            PredictionLoadingBenefitBenchmark.set(manager,"loadingPreparation",preparation);
            preparation.request(request);jobs.removeFirst().run();
            PredictionLoadingBenefitBenchmark.set(manager,"scheduledPlan",List.of());
            var publish=PredictionTileManager.class.getDeclaredMethod("publishLoadingWork");publish.setAccessible(true);publish.invoke(manager);
            assertEquals(1L,PredictionLoadingBenefitBenchmark.get(manager,"loadingDiscards"));
        }finally{dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain=remember;}
    }
    @Test void captureDirtinessRejectsLoadingCompletionBeforeTheNewMeshPublishes() throws Exception {
        boolean remember=dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain;
        dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain=false;
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                new net.minecraft.resources.ResourceLocation("minecraft","overworld"),42,-64,384,"noise","minecraft:overworld",1);
        try(var manager=new PredictionTileManager(Level.OVERWORLD,ClientTerrainSampler.custom(42,profile,(x,z)->64),
                new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime),null)) {
            var schedule=PredictionLoadingBenefitBenchmark.statusFixture(manager,80);
            var snapshot=PredictionTileManager.class.getDeclaredMethod("loadingInput",PredictionSchedulePreparation.Input.class);snapshot.setAccessible(true);
            var request=(PredictionLoadingPreparation.Input)snapshot.invoke(manager,schedule);
            var jobs=new ArrayDeque<Runnable>();
            var preparation=new PredictionClientPreparation<PredictionLoadingPreparation.Input,PredictionLoadingPreparation.Result>(jobs::add,PredictionLoadingPreparation::build);
            PredictionLoadingBenefitBenchmark.set(manager,"loadingPreparation",preparation);
            preparation.request(request);jobs.removeFirst().run();
            manager.capturedTerrainChanged(0,0);
            var publish=PredictionTileManager.class.getDeclaredMethod("publishLoadingWork");publish.setAccessible(true);publish.invoke(manager);
            assertEquals(1L,PredictionLoadingBenefitBenchmark.get(manager,"loadingDiscards"));
            assertFalse(manager.loadingProgress().planned(),"capture cannot publish stale 100% readiness");
            assertEquals(Boolean.TRUE,PredictionLoadingBenefitBenchmark.get(manager,"foregroundWorkPending"));
        }finally{dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain=remember;}
    }
}
