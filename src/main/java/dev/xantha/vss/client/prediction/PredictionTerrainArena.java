package dev.xantha.vss.client.prediction;

import java.nio.ByteBuffer;
import java.util.*;
import static org.lwjgl.opengl.GL43C.*;

/** Bounded shared geometry pages. Retired slices are reusable only after a zero-timeout fence succeeds. */
final class PredictionTerrainArena {
    // Larger pages keep opaque tiles together, reducing texture switches and
    // MDI submissions. This remains a lazy allocation, not a startup reserve.
    static final int PAGE_BYTES = configuredPageMiB() * 1024 * 1024;
    private static int configuredPageMiB() {
        int requested = Integer.getInteger("vss.indirectPageMiB", 64);
        return requested == 8 || requested == 16 || requested == 32 || requested == 64
                ? requested : 64;
    }
    /** Shared pages replace the same bytes that would otherwise live in one
     * texture buffer per tile. They are allocated lazily, so this is a ceiling
     * rather than an eager VRAM reservation. A JVM property is provided for
     * low-VRAM systems and deterministic GPU tests. */
    static final int MAX_PAGES = configuredMaxPages();
    /** Opaque and transparent geometry use separate page pools. Water is a
     * small bounded pool so transparent sorting never forces opaque pages to
     * fragment. Pages are still lazy and only allocated when water exists. */
    private static final int WATER_MAX_PAGES = Math.max(1,
            Math.min(MAX_PAGES, Integer.getInteger("vss.indirectWaterPages", 2)));
    static final PredictionTerrainArena SHARED = new PredictionTerrainArena(MAX_PAGES, "opaque");
    static final PredictionTerrainArena WATER = new PredictionTerrainArena(WATER_MAX_PAGES, "water");
    /** The overflow replaces per-tile buffers once the large-page arena fills.
     * Small lazy pages cap stranded capacity while retaining MDI and mask storage.
     * These bytes are included in the existing residency budget and retirement. */
    static final PredictionTerrainArena OVERFLOW = new PredictionTerrainArena(64, "overflow", 8 * 1024 * 1024);

    static Slice uploadOpaque(ByteBuffer data, int cells) {
        Slice slice = SHARED.upload(data, cells);
        return slice != null || Boolean.getBoolean("vss.disableIndirectOverflow")
                ? slice : OVERFLOW.upload(data, cells);
    }

    static long committedBytes() {
        return SHARED.allocatedBytes() + WATER.allocatedBytes() + OVERFLOW.allocatedBytes();
    }

    private static int configuredMaxPages() {
        int mib = Integer.getInteger("vss.indirectArenaMiB", 2048);
        mib = Math.max(128, Math.min(2048, mib));
        return Math.max(1, (int) ((long) mib * 1024L * 1024L / PAGE_BYTES));
    }
    private final List<Page> pages = new ArrayList<>();
    private final ArrayDeque<Slice> retired = new ArrayDeque<>();
    private final int maxPages;
    private final String name;
    private final int pageBytes;
    private int alignment;

    private PredictionTerrainArena(int maxPages, String name) {
        this(maxPages, name, PAGE_BYTES);
    }
    private PredictionTerrainArena(int maxPages, String name, int pageBytes) {
        this.maxPages = maxPages;
        this.name = name;
        this.pageBytes = pageBytes;
    }
    static boolean supported() {
        try { return !Boolean.getBoolean("vss.disableIndirect") && org.lwjgl.opengl.GL.getCapabilities().OpenGL46; }
        catch (IllegalStateException noContext) { return false; }
    }
    static final class Page {
        final int buffer, texture;
        final TreeMap<Integer,Integer> free = new TreeMap<>();
        int live;
        Page(int bytes) {
            buffer=glGenBuffers();texture=glGenTextures();
            glBindBuffer(GL_TEXTURE_BUFFER,buffer);glBufferData(GL_TEXTURE_BUFFER,bytes,GL_DYNAMIC_DRAW);
            glBindTexture(GL_TEXTURE_BUFFER,texture);glTexBuffer(GL_TEXTURE_BUFFER,GL_RGBA32UI,buffer);
            free.put(0,bytes);
        }
        int allocate(int bytes) {
            for(var e:free.entrySet())if(e.getValue()>=bytes){
                int start=e.getKey(),length=e.getValue();free.remove(start);
                if(length>bytes)free.put(start+bytes,length-bytes);live++;return start;
            }
            return -1;
        }
        void release(int start,int length) {
            var before=free.lowerEntry(start);
            if(before!=null && before.getKey()+before.getValue()==start){start=before.getKey();length+=before.getValue();free.remove(before.getKey());}
            var after=free.ceilingEntry(start);
            if(after!=null && start+length==after.getKey()){length+=after.getValue();free.remove(after.getKey());}
            free.put(start,length);live--;
        }
        void close(){glDeleteTextures(texture);glDeleteBuffers(buffer);}
    }
    static final class Slice {
        final PredictionTerrainArena arena;
        final Page page;final int offset,length,texture,maskOffset;
        long fence;
        Slice(PredictionTerrainArena arena,Page page,int offset,int length,int texture,int maskOffset){this.arena=arena;this.page=page;this.offset=offset;this.length=length;this.texture=texture;this.maskOffset=maskOffset;}
    }
    Slice upload(ByteBuffer data,int cells) {
        if(!supported())return null;
        reap();
        if(alignment==0) {
            if(glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE)<pageBytes/16)return null;
            alignment=Math.max(16,glGetInteger(GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT));
        }
        int payload=(data.remaining()+15)/16*16;
        int length=(payload+cells*4+alignment-1)/alignment*alignment;
        if(length>pageBytes/2)return null;
        Page selected=null;int offset=-1;
        for(Page page:pages)if((offset=page.allocate(length))>=0){selected=page;break;}
        if(selected==null){if(pages.size()>=maxPages)return null;selected=new Page(pageBytes);pages.add(selected);offset=selected.allocate(length);}
        int texture=glGenTextures();
        try {
            glBindBuffer(GL_TEXTURE_BUFFER,selected.buffer);glBufferSubData(GL_TEXTURE_BUFFER,offset,data);
            glBindTexture(GL_TEXTURE_BUFFER,texture);glTexBufferRange(GL_TEXTURE_BUFFER,GL_RGBA32UI,selected.buffer,offset,payload);
            return new Slice(this,selected,offset,length,texture,offset+payload);
        }catch(RuntimeException|Error failure){glDeleteTextures(texture);selected.release(offset,length);throw failure;}
        finally{glBindBuffer(GL_TEXTURE_BUFFER,0);glBindTexture(GL_TEXTURE_BUFFER,0);}
    }
    void mask(Slice slice,int[] values){
        if((long)values.length*4>slice.length-(slice.maskOffset-slice.offset)) throw new IllegalArgumentException("Ownership exceeds arena slice");
        glBindBuffer(GL_TEXTURE_BUFFER,slice.page.buffer);
        try{glBufferSubData(GL_TEXTURE_BUFFER,slice.maskOffset,values);}finally{glBindBuffer(GL_TEXTURE_BUFFER,0);}
    }
    /** Grow only on ownership topology changes, preserving the resident geometry on the GPU. */
    Slice resizeMask(Slice slice,int cells) {
        if((long)cells*4<=slice.length-(slice.maskOffset-slice.offset)) return slice;
        if(slice.arena!=this) return slice.arena.resizeMask(slice,cells);
        int payload=slice.maskOffset-slice.offset;
        int length=(payload+cells*4+alignment-1)/alignment*alignment;
        if(length>pageBytes/2) return null;
        Page selected=null; int offset=-1;
        for(Page page:pages) if((offset=page.allocate(length))>=0) { selected=page;break; }
        if(selected==null) {
            if(pages.size()>=maxPages) return null;
            selected=new Page(pageBytes);pages.add(selected);offset=selected.allocate(length);
        }
        int texture=glGenTextures();
        try {
            glBindBuffer(GL_COPY_READ_BUFFER,slice.page.buffer);glBindBuffer(GL_COPY_WRITE_BUFFER,selected.buffer);
            glCopyBufferSubData(GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,slice.offset,offset,payload);
            glBindTexture(GL_TEXTURE_BUFFER,texture);glTexBufferRange(GL_TEXTURE_BUFFER,GL_RGBA32UI,selected.buffer,offset,payload);
            var grown=new Slice(this,selected,offset,length,texture,offset+payload);
            retire(slice);return grown;
        } catch(RuntimeException|Error failure) { glDeleteTextures(texture);selected.release(offset,length);throw failure; }
        finally { glBindBuffer(GL_COPY_READ_BUFFER,0);glBindBuffer(GL_COPY_WRITE_BUFFER,0);glBindTexture(GL_TEXTURE_BUFFER,0); }
    }
    void retire(Slice slice) {
        if(slice==null)return;
        if(slice.arena!=this){slice.arena.retire(slice);return;}
        glDeleteTextures(slice.texture);
        slice.fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);retired.addLast(slice);
        reap();
    }
    private void reapLocal(){
        for(int count=0;count<16&&!retired.isEmpty();count++){
            Slice slice=retired.peekFirst();int result=glClientWaitSync(slice.fence,0,0);
            // Fences are in submission order. Later slices cannot become reusable first.
            if(result!=GL_ALREADY_SIGNALED && result!=GL_CONDITION_SATISFIED)break;
            glDeleteSync(slice.fence);slice.page.release(slice.offset,slice.length);retired.removeFirst();
        }
        for(var it=pages.iterator();it.hasNext();){Page page=it.next();if(page.live==0){page.close();it.remove();}}
    }
    void reap(){
        reapLocal();
        if (this == SHARED) { WATER.reapLocal(); OVERFLOW.reapLocal(); }
    }
    private void closeLocal(){
        for(Slice slice:retired)glDeleteSync(slice.fence);retired.clear();
        for(Page page:pages)page.close();pages.clear();alignment=0;
    }
    void close(){
        closeLocal();
        // Existing callers historically closed SHARED directly. Keep that
        // lifecycle contract while also disposing the dedicated water pool.
        if (this == SHARED) { WATER.closeLocal(); OVERFLOW.closeLocal(); }
    }
    private String diagnosticsLocal(){return "arena="+name+"{pages="+pages.size()+",bytes="+allocatedBytes()
            +",budgetBytes="+(long)maxPages*pageBytes+",retired="+retired.size()+"}";}
    String diagnostics(){return diagnosticsLocal()+","+WATER.diagnosticsLocal()+","+OVERFLOW.diagnosticsLocal();}
    long allocatedBytes() { return (long) pages.size() * pageBytes; }
}
