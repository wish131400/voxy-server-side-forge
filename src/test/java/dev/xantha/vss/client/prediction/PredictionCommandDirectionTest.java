package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PredictionCommandDirectionTest {
    @Test void sixNormalsDoubleSidedModelsSlopesFluidsAndMixedCommandsRemainConservative() {
        for(int axis=0;axis<3;axis++)for(boolean positive:new boolean[]{false,true}) {
            int[] face=face(axis,positive);int dimension=axis==1?0:axis==0?1:2;
            assertEquals(1<<(dimension*2+(positive?1:0)),new PredictionMeshletBounds(face).directions[0]);
        }
        var plant=face(1,true);plant[6]|=PredictionPackedMesh.FLAG_UNSHADED;
        assertEquals(64,new PredictionMeshletBounds(plant).directions[0]);
        var model=plant.clone();model[6]|=PredictionPackedMesh.FLAG_MODEL_UV;
        assertEquals(2,new PredictionMeshletBounds(model).directions[0]);
        var slope=face(0,true);slope[4]+=1;
        assertEquals(64,new PredictionMeshletBounds(slope).directions[0]);
        var water=face(0,true);water[6]|=1<<22;
        assertEquals(64,new PredictionMeshletBounds(water).directions[0]);
        var mixed=new int[24];System.arraycopy(face(2,false),0,mixed,0,12);System.arraycopy(face(2,true),0,mixed,12,12);
        assertEquals(48,new PredictionMeshletBounds(mixed).directions[0]);
        System.arraycopy(plant,0,mixed,12,12);assertNotEquals(0,new PredictionMeshletBounds(mixed).directions[0]&64);
    }
    private static int[] face(int axis,boolean positive) {
        int y=32768+64*4;
        int[] words={0|1<<16,1,0,1|1<<16,y|y<<16,y|y<<16,axis<<24,0,0,0,0,0};
        if(axis==0&&!positive)words[6]|=PredictionPackedMesh.FLAG_BANK;
        if(axis!=0&&positive)words[6]|=PredictionPackedMesh.FLAG_FACE_POSITIVE;
        if(axis==1)words[0]=words[1]=16|16<<16;
        if(axis==2)words[2]=words[3]=32|32<<16;
        return words;
    }
}
