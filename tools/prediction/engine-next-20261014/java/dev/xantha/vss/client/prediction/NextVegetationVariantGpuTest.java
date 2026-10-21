package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.glfw.GLFW.*;
import java.util.*;
import java.nio.file.*;
import java.lang.reflect.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;

/** Research only: preserve footprint, alpha, derivatives, ownership and depth semantics. */
class NextVegetationVariantGpuTest {
    private static final int SIZE=512;
    private record Image(byte[] color,float[] depth){}
    private record Item(int id,int span,float lo,float hi,PredictionGpuTile gpu,PredictionRenderer.Draw draw,PredictionPackedMesh packed,
                        PredictionDrawRanges all,PredictionDrawRanges other,PredictionDrawRanges features,long partitionNs,int retainedBytes){}
    @Test void recordStrictQualityAdmissionAndCompleteCostOfResearchOnlyVariants()throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(SIZE,SIZE,"VSS finite shader variant",0,0);assertNotEquals(0,window);
        var textures=new ArrayList<Integer>();var items=new ArrayList<Item>();
        var rows=new ArrayList<String>();rows.add("mesh,distance,case,round,mode,cpu_ns,gpu_ns,calls");
        var through=new ArrayList<String>();through.add("mesh,distance,case,round,mode,frames,elapsed_ns");
        var quality=new ArrayList<String>();quality.add("mesh,distance,yaw,mode,changed_color_bytes,max_color_delta,max_depth_delta,partition_ns,retained_range_bytes,feature_ranges,other_ranges");
        var root=Path.of(System.getProperty("vss.engineInputs","tools/prediction/engine-render-20261014/inputs"));
        int vao=0,ebo=0,query=0;TextureTarget target=null;
        try{
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            System.out.println("NEXT_VARIANT_GPU "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION));
            invoke(CanopyMaterialQualityGpuTest.class,"setupTextures",new Class<?>[]{Path.class,List.class},root,textures);
            int main=PredictionVegetationRenderGpuTest.texture(textures,7,SIZE,SIZE,GL_R32F,GL_RED,new float[SIZE*SIZE]);
            PredictionVegetationRenderGpuTest.texture(textures,8,1,1,GL_R8,GL_RED,new float[]{0});
            glActiveTexture(GL_TEXTURE6);int volume=glGenTextures();textures.add(volume);glBindTexture(GL_TEXTURE_3D,volume);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage3D(GL_TEXTURE_3D,0,GL_R8,1,1,1,0,GL_RED,GL_FLOAT,new float[]{0});
            @SuppressWarnings("unchecked") var fixtures=(List<Object>)invoke(CanopyMaterialQualityGpuTest.class,"read",new Class<?>[]{Path.class},root);
            for(Object fixture:fixtures){
                int id=((Number)read(fixture,"id")).intValue(),span=((Number)read(fixture,"span")).intValue(),spacing=((Number)read(fixture,"spacing")).intValue();
                float lo=((Number)read(fixture,"minY")).floatValue(),hi=((Number)read(fixture,"maxY")).floatValue();
                int[] words=((int[][])read(fixture,"words"))[0];var packed=PredictionPackedMesh.terrainRecords(words,span/spacing);packed.prepareGpuStorage();
                var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
                var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],PredictionMesh.restored(words.length/12*6,0,packed,null),new PredictionDepthBound((int)lo,(int)Math.ceil(hi)),0,id,span/spacing,spacing);
                var gpu=new PredictionGpuTile(key);assertTrue(gpu.ensureMesh(tile));
                boolean[] all=new boolean[(span/spacing)*(span/spacing)];Arrays.fill(all,true);gpu.updateCoverage(all);byte[] mask=new byte[all.length];Arrays.fill(mask,(byte)192);gpu.updateBoundaryCoverage(mask);
                var draw=new PredictionRenderer.Draw(tile,gpu,all,all,false,31,0,new AABB(0,lo,0,span,hi,span),true);
                long start=System.nanoTime();int[] canonical=packed.quads();var ranges=packed.drawRanges(false,31);
                var features=partition(canonical,ranges,true);var other=partition(canonical,ranges,false);long partition=System.nanoTime()-start;
                assertEquals(ranges.quads,features.quads+other.quads);
                items.add(new Item(id,span,lo,hi,gpu,draw,packed,ranges,other,features,partition,(features.first.length+other.first.length)*8));
            }
            vao=glGenVertexArrays();ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
            int max=items.stream().mapToInt(i->i.packed.quadCount()).max().orElseThrow();int[] indices=new int[max*6],corners={0,1,2,0,2,3};for(int q=0;q<max;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
            target=new TextureTarget(SIZE,SIZE,true,false);query=glGenQueries();
            try(var general=PredictionTerrainProgram.createBatch();var specialized=specialized();
                var full=new PredictionIndirectBatch();var splitOther=new PredictionIndirectBatch();var splitGeneral=new PredictionIndirectBatch();var splitFeature=new PredictionIndirectBatch()){
                for(var item:items)for(int distance:new int[]{128,1024,4096}){
                    for(int yaw:new int[]{0,45,90,135}){
                        var camera=camera(item,distance,yaw);var projection=configure(general,item,camera,main);configure(specialized,item,camera,main);
                        Image reference=null,partitionReference=null;
                        for(int mode=0;mode<4;mode++){
                            clear(target,vao);draw(mode,general,specialized,item,camera,full,splitOther,splitGeneral,splitFeature);Image image=capture();
                            if(mode==0)reference=image;if(mode==1)partitionReference=image;
                            if(mode==2){assertArrayEquals(partitionReference.color,image.color,"variant vs identical split input "+item.id);assertArrayEquals(partitionReference.depth,image.depth,1e-7f);}
                            // This is an admission experiment. Record rejected candidates too,
                            // so a strict pixel mismatch does not discard their full-cost data.
                            quality.add(quality(item,distance,yaw,mode,reference,image));
                        }
                    }
                    for(String scenario:List.of("stable","turn")){
                        for(int round=-60;round<81;round++)for(int order=0;order<4;order++){
                            int mode=Math.floorMod(round+order,4);var camera=camera(item,distance,scenario.equals("turn")?round*1.3:0);
                            configure(general,item,camera,main);configure(specialized,item,camera,main);clear(target,vao);glFinish();
                            long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,query);long calls=draw(mode,general,specialized,item,camera,full,splitOther,splitGeneral,splitFeature);glEndQuery(GL_TIME_ELAPSED);
                            long cpu=System.nanoTime()-start,gpu=glGetQueryObjectui64(query,GL_QUERY_RESULT);if(round>=0)rows.add(item.id+","+distance+","+scenario+","+round+","+mode+","+cpu+","+gpu+","+calls);
                        }
                        for(int round=-3;round<11;round++)for(int order=0;order<4;order++){
                            int mode=Math.floorMod(round+order,4);glFinish();long start=System.nanoTime();int frames=24;
                            for(int frame=0;frame<frames;frame++){
                                var camera=camera(item,distance,scenario.equals("turn")?frame*1.3:0);configure(general,item,camera,main);configure(specialized,item,camera,main);clear(target,vao);
                                draw(mode,general,specialized,item,camera,full,splitOther,splitGeneral,splitFeature);
                            }
                            glFinish();if(round>=0)through.add(item.id+","+distance+","+scenario+","+round+","+mode+","+frames+","+(System.nanoTime()-start));
                        }
                    }
                    assertEquals(GL_NO_ERROR,glGetError());
                }
                compareLargeForest(general,specialized,items,target,vao,main,query,rows,through,quality);
            }
            CompletionGpuFixture.save("next-variant.csv",rows);CompletionGpuFixture.save("next-variant-quality.csv",quality);CompletionGpuFixture.save("next-variant-throughput.csv",through);
            var admission=new ArrayList<String>();admission.add("mode,color_exact,max_color_delta,max_depth_delta,quality_pass,production_enabled");
            for(int mode:new int[]{2,3}){
                boolean exact=true;int maxColor=0;double maxDepth=0;
                for(int i=1;i<quality.size();i++){
                    String[] value=quality.get(i).split(",");if(Integer.parseInt(value[3])!=mode)continue;
                    exact&=Integer.parseInt(value[4])==0;maxColor=Math.max(maxColor,Integer.parseInt(value[5]));maxDepth=Math.max(maxDepth,Double.parseDouble(value[6]));
                }
                boolean qualityPass=exact&&maxDepth<=1e-7;
                admission.add(mode+","+exact+","+maxColor+","+maxDepth+","+qualityPass+",false");
                System.out.println("NEXT_VARIANT_ADMISSION mode="+mode+" qualityPass="+qualityPass+" maxColorDelta="+maxColor+" maxDepthDelta="+maxDepth+" productionEnabled=false");
            }
            CompletionGpuFixture.save("next-variant-admission.csv",admission);
        }finally{
            if(query!=0)glDeleteQueries(query);if(target!=null)target.destroyBuffers();if(ebo!=0)glDeleteBuffers(ebo);if(vao!=0)glDeleteVertexArrays(vao);
            items.forEach(i->i.gpu.close());glFinish();PredictionTerrainArena.SHARED.close();PredictionTerrainArena.WATER.close();PredictionQuadBufferPool.SHARED.close();textures.forEach(i->glDeleteTextures(i));glfwDestroyWindow(window);glfwTerminate();
        }
    }
    private static void compareLargeForest(PredictionTerrainProgram general,PredictionTerrainProgram special,List<Item> inputs,TextureTarget target,int vao,int main,int query,List<String> rows,List<String> through,List<String> quality)throws Exception{
        for(int count:new int[]{64,128}){
            int side=(int)Math.ceil(Math.sqrt(count));var draws=new ArrayList<PredictionRenderer.Draw>();var lookup=new IdentityHashMap<PredictionRenderer.Draw,Item>();
            for(int i=0;i<count;i++){
                Item item=inputs.get(i%inputs.size());var original=item.draw.tile();int x=i%side,z=i/side;
                var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,x,z,0);
                var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],original.mesh(),original.depthBound(),0,i+1,original.cellAxis(),original.spacingBlocks());
                var draw=new PredictionRenderer.Draw(tile,item.gpu,item.draw.allowed(),item.draw.features(),false,31,0,new AABB(tile.baseBlockX(),item.lo,tile.baseBlockZ(),tile.baseBlockX()+item.span,item.hi,tile.baseBlockZ()+item.span),true);
                draws.add(draw);lookup.put(draw,item);
            }
            try(var full=new PredictionIndirectBatch();var other=new PredictionIndirectBatch();var splitGeneral=new PredictionIndirectBatch();var feature=new PredictionIndirectBatch()){
                for(int distance:new int[]{1024,2048})for(String scenario:List.of("stable","turn")){
                    double center=side*128*.5;
                    for(int round=-60;round<81;round++){
                        double yaw=scenario.equals("turn")?round*.012:0;
                        var camera=new Vec3(center+Math.sin(yaw)*distance,110,center+Math.cos(yaw)*distance);
                        var reference=inputs.get(0);configureForest(general,camera,center,main);configureForest(special,camera,center,main);
                        Image[] images=new Image[4];
                        for(int order=0;order<4;order++){
                            int mode=Math.floorMod(round+order,4);clear(target,vao);glFinish();long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,query);
                            long calls=forestDraw(mode,general,special,draws,lookup,camera,full,other,splitGeneral,feature);glEndQuery(GL_TIME_ELAPSED);
                            long cpu=System.nanoTime()-start,gpu=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                            if(round>=0)rows.add(-count+","+distance+","+scenario+","+round+","+mode+","+cpu+","+gpu+","+calls);
                            if(round==0||round==80)images[mode]=capture();
                        }
                        if(images[0]!=null)for(int mode=0;mode<4;mode++)quality.add(quality(reference,distance,round,mode,images[0],images[mode]).replaceFirst("^[^,]+,",(-count)+","));
                    }
                    for(int round=-3;round<11;round++)for(int order=0;order<4;order++){
                        int mode=Math.floorMod(round+order,4);glFinish();long start=System.nanoTime();int frames=24;
                        for(int frame=0;frame<frames;frame++){
                            double yaw=scenario.equals("turn")?frame*.012:0;var camera=new Vec3(center+Math.sin(yaw)*distance,110,center+Math.cos(yaw)*distance);
                            configureForest(general,camera,center,main);configureForest(special,camera,center,main);clear(target,vao);
                            forestDraw(mode,general,special,draws,lookup,camera,full,other,splitGeneral,feature);
                        }
                        glFinish();if(round>=0)through.add(-count+","+distance+","+scenario+","+round+","+mode+","+frames+","+(System.nanoTime()-start));
                    }
                }
            }
        }
    }
    private static void configureForest(PredictionTerrainProgram shader,Vec3 camera,double center,int main){
        Item dummy=new Item(0,(int)(center*2),64,90,null,null,null,null,null,null,0,0);configure(shader,dummy,camera,main);
    }
    private static long forestDraw(int mode,PredictionTerrainProgram general,PredictionTerrainProgram special,List<PredictionRenderer.Draw> draws,IdentityHashMap<PredictionRenderer.Draw,Item> lookup,Vec3 camera,PredictionIndirectBatch full,PredictionIndirectBatch other,PredictionIndirectBatch splitGeneral,PredictionIndirectBatch feature){
        if(mode==3){long calls=0;for(var draw:draws)calls+=ordered(general,special,lookup.get(draw),draw,camera);return calls;}
        if(mode==0)return forestSubmit(general,draws,lookup,0,camera,full);
        return forestSubmit(general,draws,lookup,1,camera,other)+forestSubmit(mode==1?general:special,draws,lookup,2,camera,mode==1?splitGeneral:feature);
    }
    private static long forestSubmit(PredictionTerrainProgram shader,List<PredictionRenderer.Draw> draws,IdentityHashMap<PredictionRenderer.Draw,Item> lookup,int part,Vec3 camera,PredictionIndirectBatch batch){
        shader.use();batch.begin(shader);long replay=batch.replayPass(draws,part,false,camera,Long.MAX_VALUE);
        if(replay<0)for(var draw:draws){Item item=lookup.get(draw);var ranges=part==0?item.all:part==1?item.other:item.features;if(ranges.quads>0)assertTrue(batch.add(draw,ranges,camera,false,false,Long.MAX_VALUE));}
        return batch.end();
    }
    private static PredictionTerrainProgram specialized()throws Exception {
        var type=PredictionTerrainProgram.class;var vf=type.getDeclaredField("TERRAIN_VERTEX");var ff=type.getDeclaredField("TERRAIN_FRAGMENT");vf.setAccessible(true);ff.setAccessible(true);
        String fragment=(String)ff.get(null);
        for(String[] definition:new String[][]{{"float","vWater","0.0"},{"uint","vFeature","1u"},{"uint","vRealBoundary","0u"},{"uint","vTerrainWall","0u"},{"uint","vDisplayApproximate","0u"}})
            fragment=fragment.replace("flat in "+definition[0]+" "+definition[1]+";","const "+definition[0]+" "+definition[1]+" = "+definition[2]+";");
        var constructor=type.getDeclaredConstructor(String.class,String.class);constructor.setAccessible(true);
        return constructor.newInstance(PredictionTerrainProgram.batchVertex((String)vf.get(null)),PredictionTerrainProgram.batchFragment(fragment));
    }
    private static PredictionDrawRanges partition(int[] words,PredictionDrawRanges all,boolean wanted){
        int[] first=new int[all.quads],count=new int[all.quads];int size=0;
        for(int r=0;r<all.first.length;r++)for(int q=all.first[r];q<all.first[r]+all.count[r];q++){
            int p=q*12;boolean eligible=(words[p+10]&PredictionPackedMesh.FEATURE)!=0&&(words[p+10]&PredictionPackedMesh.DISPLAY_APPROXIMATE)==0
                    &&(words[p+6]>>>22&3)==0&&(words[p+9]>>>25&7)==0;
            if(eligible!=wanted)continue;if(size>0&&first[size-1]+count[size-1]==q)count[size-1]++;else{first[size]=q;count[size++]=1;}
        }
        return new PredictionDrawRanges(Arrays.copyOf(first,size),Arrays.copyOf(count,size));
    }
    private static long draw(int mode,PredictionTerrainProgram general,PredictionTerrainProgram special,Item item,Vec3 camera,PredictionIndirectBatch full,PredictionIndirectBatch other,PredictionIndirectBatch splitGeneral,PredictionIndirectBatch feature){
        if(mode==3)return ordered(general,special,item,item.draw,camera);
        if(mode==0)return submit(general,item,item.all,camera,full);
        return submit(general,item,item.other,camera,other)+submit(mode==1?general:special,item,item.features,camera,mode==1?splitGeneral:feature);
    }
    private static long submit(PredictionTerrainProgram shader,Item item,PredictionDrawRanges ranges,Vec3 camera,PredictionIndirectBatch batch){
        if(ranges.quads==0)return 0;shader.use();batch.begin(shader);long replay=batch.replayPass(ranges,1,false,camera,Long.MAX_VALUE);
        if(replay<0&&!batch.add(item.draw,ranges,camera,false,false,Long.MAX_VALUE)){
            batch.end();shader.batch(false);item.gpu.bindTerrain(shader);item.gpu.bindYield(3);shader.setTile((float)-camera.x,(float)-camera.y,(float)-camera.z,item.draw.tile().spacingBlocks(),item.draw.tile().cellAxis(),false);shader.setMorph(null,0);shader.setBoundaryReplacement(true);PredictionRenderer.submitRanges(ranges,item.packed,false);return 1;
        }
        return batch.end();
    }
    private static long ordered(PredictionTerrainProgram general,PredictionTerrainProgram special,Item item,PredictionRenderer.Draw draw,Vec3 camera){
        int a=0,b=0;long calls=0;
        while(a<item.other.first.length||b<item.features.first.length){
            boolean feature=b<item.features.first.length&&(a==item.other.first.length||item.features.first[b]<item.other.first[a]);
            var shader=feature?special:general;var ranges=feature?item.features:item.other;int at=feature?b++:a++;
            shader.use();shader.batch(false);item.gpu.bindTerrain(shader);item.gpu.bindYield(3);shader.setTile((float)(draw.tile().baseBlockX()-camera.x),(float)-camera.y,(float)(draw.tile().baseBlockZ()-camera.z),draw.tile().spacingBlocks(),item.packed.cellAxis(),false);shader.setMorph(null,0);shader.setBoundaryReplacement(true);
            int first=item.packed.gpuFirst(ranges.first[at],ranges.count[at],false);glDrawElements(GL_TRIANGLES,ranges.count[at]*6,GL_UNSIGNED_INT,first*24L);calls++;
        }
        return calls;
    }
    private static Vec3 camera(Item i,int distance,double yaw){double a=Math.toRadians(yaw),c=i.span*.5,y=(i.lo+i.hi)*.5;return new Vec3(c+Math.sin(a)*distance,y+i.span*.12,c+Math.cos(a)*distance);}
    private static VssLodProjection.MatrixData configure(PredictionTerrainProgram shader,Item item,Vec3 camera,int main){
        double c=item.span*.5;var direction=new Vec3(c,(item.lo+item.hi)*.5,c).subtract(camera).normalize();var model=new Matrix4f().lookAlong((float)direction.x,(float)direction.y,(float)direction.z,0,1,0);
        var projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,16384));
        shader.use();shader.setLeaves(false,CanopySurfaceIntegrationTest.leaves());shader.setSamplers(0,1,2,3,4);shader.setFrame(new float[]{0,0,0,0},1e7f,2e7f,0,1e7f,false,1);shader.setCamera(model,projection.matrix());shader.bindMainDepth(main,projection);shader.setOpaqueAlpha(1);shader.setRealCoverage(0,0,0,0);shader.setRealRenderDistance(0);shader.setVoxyOwnershipDistance(0);shader.setHorizon(131072);shader.setDetailDistance(0);shader.setMorph(null,0);
        int id=glGetInteger(GL_CURRENT_PROGRAM);glUniform1fv(glGetUniformLocation(id,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});glUniform1i(glGetUniformLocation(id,"ExactCoverage"),8);glUniform3f(glGetUniformLocation(id,"ExactCoverageGrid"),1e6f,1e6f,1);
        glUniform1i(glGetUniformLocation(id,"VanillaMask"),6);glUniform3i(glGetUniformLocation(id,"VanillaMaskSize"),1,1,1);glUniform3f(glGetUniformLocation(id,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);return projection;
    }
    private static void clear(TextureTarget target,int vao){target.bindWrite(true);glBindVertexArray(vao);glViewport(0,0,SIZE,SIZE);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);glClearDepth(0);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);}
    private static Image capture(){var cb=BufferUtils.createByteBuffer(SIZE*SIZE*4);var db=BufferUtils.createFloatBuffer(SIZE*SIZE);glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,cb);glReadPixels(0,0,SIZE,SIZE,GL_DEPTH_COMPONENT,GL_FLOAT,db);byte[] c=new byte[cb.remaining()];float[] d=new float[db.remaining()];cb.get(c);db.get(d);return new Image(c,d);}
    private static String quality(Item item,int distance,int yaw,int mode,Image a,Image b){int changed=0,max=0;float depth=0;for(int i=0;i<a.color.length;i++){int delta=Math.abs(Byte.toUnsignedInt(a.color[i])-Byte.toUnsignedInt(b.color[i]));if(delta!=0)changed++;max=Math.max(max,delta);}for(int i=0;i<a.depth.length;i++)depth=Math.max(depth,Math.abs(a.depth[i]-b.depth[i]));return item.id+","+distance+","+yaw+","+mode+","+changed+","+max+","+depth+","+item.partitionNs+","+item.retainedBytes+","+item.features.first.length+","+item.other.first.length;}
    private static Object read(Object o,String name)throws Exception{var m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}
    private static Object invoke(Class<?> type,String name,Class<?>[] args,Object... values)throws Exception{var m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m.invoke(null,values);}
}
