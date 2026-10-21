package dev.xantha.vss.client.prediction;

import java.io.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Shared test fixtures and optional local-corpus measurements; never packaged with the mod. */
final class AlgorithmSupport {
    static volatile long sink;
    static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    record Forest(int id,int span,int spacing,float minY,float maxY,int[][] words) { }
    record Measurement(long nanos,long bytes) { }
    interface Work { long run() throws Exception; }
    static List<Forest> forests() throws IOException {
        var result=new ArrayList<Forest>();
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(inputs().resolve("forest-fixtures.bin"))))) {
            int n=in.readInt();
            for(int i=0;i<n;i++) {
                int id=in.readInt(),span=in.readInt(),spacing=in.readInt();float lo=in.readFloat(),hi=in.readFloat();int[][] words=new int[3][];
                for(int mode=0;mode<3;mode++) {words[mode]=new int[in.readInt()];for(int p=0;p<words[mode].length;p++)words[mode][p]=in.readInt();}
                result.add(new Forest(id,span,spacing,lo,hi,words));
            }
            if(in.read()!=-1)throw new IOException("Trailing forest input");
        }
        return result;
    }
    static Path inputs() {return Path.of(System.getProperty("vss.algorithmInputs"));}
    static Path output() throws IOException {var p=Path.of(System.getProperty("vss.algorithmResults"));Files.createDirectories(p);return p;}
    static void save(String name,List<String> rows) throws IOException {Files.write(output().resolve(name),rows);}
    static Measurement measure(Work work) throws Exception {
        long id=Thread.currentThread().getId(),bytes=THREADS.getThreadAllocatedBytes(id),start=System.nanoTime();
        sink=work.run();return new Measurement(System.nanoTime()-start,THREADS.getThreadAllocatedBytes(id)-bytes);
    }
    static long median(long[] values) {var copy=values.clone();Arrays.sort(copy);return copy[copy.length/2];}
    static ClientColumnSample sample(int height,int flags) {
        return new ClientColumnSample(height,height,0,ClientColumnSample.NO_BLOCK,0,0,0,0,0,flags,0,
            ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
    }
    static PredictionTileManager.PredictionTile tile(int x,int z,int height,int version) {
        int[] heights=new int[65*65],colors=new int[heights.length];Arrays.fill(heights,height);Arrays.fill(colors,0xff44aa55);
        var samples=new ClientColumnSample[heights.length];Arrays.fill(samples,sample(height,PredictionWallEvidence.CHECKED));
        var mesh=PredictionMeshBuilder.build(samples,colors,63,0,2,65,false).compactForRendering();
        var key=new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,x,z,1);
        var tile=new PredictionTileManager.PredictionTile(key,heights,heights,samples,mesh,new PredictionDepthBound(height,height),0,version,64,2);
        mesh.prepareGpuPayload(tile);return tile;
    }
    static PredictionTileManager.PredictionTile version(PredictionTileManager.PredictionTile tile,int version) {
        return new PredictionTileManager.PredictionTile(tile.key(),tile.heights(),tile.groundHeights(),tile.samples(),tile.mesh(),tile.depthBound(),0,version,tile.cellAxis(),tile.spacingBlocks());
    }
}
