package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReflectionAccessTest {
    static class Parent { private int inherited = 17; }
    public static class Api extends Parent {
        private int width = 1920, height = 1080, frame = 101;
        private double cameraX = 3.125, cameraY = 78, cameraZ = -8192;
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int patch(int value) { return value+1; }
        public int patch(int value,int extra) { return value+extra; }
        public String typed(String value) { return value; }
        public int typed(Integer value) { return value; }
        public void fail() { throw new IllegalArgumentException("expected"); }
        public int a(){return 1;} public int b(){return 2;} public int c(){return 3;} public int d(){return 4;}
        public int e(){return 5;} public int f(){return 6;} public int g(){return 7;} public int h(){return 8;}
    }
    private static volatile long consumed;
    @Test void inheritedPrivateFieldsTypedOverloadsAndFailuresKeepTheirMeaning() throws Exception {
        var api=new Api();assertEquals(17,ReflectionAccess.field(api,"inherited"));
        assertEquals(4,ReflectionAccess.call(api,"patch",3));assertEquals(7,ReflectionAccess.call(api,"patch",3,4));
        assertEquals("x",ReflectionAccess.method(Api.class,"typed",String.class).invoke(api,"x"));
        assertEquals(5,ReflectionAccess.method(Api.class,"typed",Integer.class).invoke(api,5));
        assertSame(ReflectionAccess.method(Api.class,"getWidth",0),ReflectionAccess.method(Api.class,"getWidth",0));
        assertThrows(NoSuchFieldException.class,()->ReflectionAccess.field(api,"absent"));
        assertThrows(NoSuchMethodException.class,()->ReflectionAccess.call(api,"absent"));
        var error=assertThrows(InvocationTargetException.class,()->ReflectionAccess.call(api,"fail"));
        assertInstanceOf(IllegalArgumentException.class,error.getCause());
    }
    @Test void measureRepeatedViewportAndPacketMemberResolution() throws Exception {
        var api=new Api();var rows=new ArrayList<String>();rows.add("round,mode,operations,elapsed_ns");
        long[][] times=new long[2][41];
        for(int round=-20;round<41;round++)for(int order=0;order<2;order++) {
            int mode=Math.floorMod(round+order,2);long sum=0,start=System.nanoTime();
            for(int i=0;i<4096;i++) {
                for(String name:new String[]{"width","height","frame","cameraX","cameraY","cameraZ","inherited"})
                    sum+=((Number)(mode==0?oldField(api,name):ReflectionAccess.field(api,name))).longValue();
                for(String name:new String[]{"getWidth","getHeight","a","h"})
                    sum+=((Number)(mode==0?oldCall(api,name):ReflectionAccess.call(api,name))).longValue();
                Method method=mode==0?Api.class.getMethod("typed",Integer.class):ReflectionAccess.method(Api.class,"typed",Integer.class);
                sum+=((Number)method.invoke(api,5)).longValue();
            }
            long elapsed=System.nanoTime()-start;consumed=sum;
            if(round>=0){times[mode][round]=elapsed;rows.add(round+","+mode+",49152,"+elapsed);}
        }
        for(var a:times)Arrays.sort(a);Path output=Path.of(System.getProperty("vss.resourceResults", "build/resource-experiment-results"));Files.createDirectories(output);
        Files.write(output.resolve("reflection.csv"),rows);
        System.out.printf(Locale.ROOT,"REFLECTION operations=49152 median_ms=%.6f->%.6f rounds=41%n",times[0][20]/1e6,times[1][20]/1e6);
    }
    private static Object oldField(Object owner,String name)throws Exception {
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(NoSuchFieldException absent){}
        }
        throw new NoSuchFieldException(name);
    }
    private static Object oldCall(Object owner,String name,Object...args)throws Exception {
        for(Method m:owner.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(owner,args);}
        throw new NoSuchMethodException(name);
    }
}
