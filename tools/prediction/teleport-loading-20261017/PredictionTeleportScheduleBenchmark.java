package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** Isolates the measured unused resident sort, with identical live production inputs. */
class PredictionTeleportScheduleBenchmark {
    @Test void compareWholeSchedulingWithAndWithoutUnusedReclaimSort() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        int previousDistance=dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks;
        dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks=8192;
        try(var manager=PredictionTeleportLoadingTest.manager((x,z)->64,2)) {
            int top=manager.layout().levelCount()-1;
            var root=new PredictionTileKey(Level.OVERWORLD,0,0,top);
            PredictionTeleportLoadingTest.residents(manager).put(root,PredictionTeleportLoadingTest.tile(manager,root,8));
            var keys=new ArrayList<PredictionTileKey>();var targets=new HashMap<PredictionTileKey,Integer>();
            for(int index=0;index<7000;index++) {
                var key=new PredictionTileKey(Level.OVERWORLD,index%100,index/100,0);
                PredictionTeleportLoadingTest.residents(manager).put(key,PredictionTeleportLoadingTest.tile(manager,key,8));
                if(index<1800){keys.add(key);targets.put(key,8);}
            }
            PredictionTeleportLoadingTest.set(manager,"scheduledPlan",List.copyOf(keys));
            PredictionTeleportLoadingTest.keys(manager,"desiredKeys").addAll(keys);
            PredictionTeleportLoadingTest.set(manager,"ordinaryTargets",Map.copyOf(targets));
            PredictionTeleportLoadingTest.set(manager,"terrainTargets",Map.copyOf(targets));
            var input=(PredictionSchedulePreparation.Input)PredictionTeleportLoadingTest.invoke(manager,"schedulingInput",System.nanoTime());
            var components=PredictionSchedulePreparation.Input.class.getRecordComponents();
            Object[] fields=new Object[components.length];
            for(int index=0;index<components.length;index++)fields[index]=components[index].getAccessor().invoke(input);
            fields[fields.length-1]=true;
            var alwaysSort=PredictionSchedulePreparation.Input.class.getDeclaredConstructor(
                    Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(fields);
            var without=PredictionSchedulePreparation.build(input);var with=PredictionSchedulePreparation.build(alwaysSort);
            assertEquals(with.work(),without.work());assertEquals(with.cached(),without.cached());assertEquals(with.hints(),without.hints());
            assertEquals(7000,with.reclaim().size());assertTrue(without.reclaim().isEmpty());
            for(int warm=0;warm<12;warm++){PredictionSchedulePreparation.build(alwaysSort);PredictionSchedulePreparation.build(input);}
            var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
            long thread=Thread.currentThread().getId();long[] before=new long[32],after=new long[32];
            long beforeBytes=0,afterBytes=0;long checksum=0;
            for(int round=0;round<32;round++) {
                for(int side=0;side<2;side++) {
                    boolean legacy=(round+side)%2==0;
                    long allocated=bean.getThreadAllocatedBytes(thread),started=System.nanoTime();
                    var result=PredictionSchedulePreparation.build(legacy?alwaysSort:input);
                    long elapsed=System.nanoTime()-started,bytes=bean.getThreadAllocatedBytes(thread)-allocated;
                    checksum+=result.hints().size()+result.reclaim().size();
                    if(legacy){before[round]=elapsed;beforeBytes+=bytes;}else{after[round]=elapsed;afterBytes+=bytes;}
                }
            }
            Arrays.sort(before);Arrays.sort(after);
            String json="{\"residentTiles\":7001,\"plannedTiles\":1800,\"rounds\":32,\"checksum\":"+checksum
                    +",\"alwaysReclaimMedianMs\":"+before[16]/1e6+",\"onDemandReclaimMedianMs\":"+after[16]/1e6
                    +",\"alwaysReclaimMeanAllocatedBytes\":"+beforeBytes/32+",\"onDemandReclaimMeanAllocatedBytes\":"+afterBytes/32+"}";
            Path output=Path.of(System.getProperty("vss.teleportBenchmark"));Files.createDirectories(output.getParent());Files.writeString(output,json+"\n");
        }finally{dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks=previousDistance;}
    }
}
