package dev.xantha.vss.client.prediction;

/** Replaces stale heightfield margin walls only where a selected neighbour can close the edge. */
final class PredictionBoundaryWalls {
    private PredictionBoundaryWalls() { }

    static byte[] build(PredictionLodSeams.Surface surface, PredictionLodSeams.Index index) {
        var tile = surface.tile();
        int axis = tile.cellAxis(), step = tile.spacingBlocks();
        byte[] mask = new byte[surface.nodes()];
        for (int node = 0; node < mask.length; node++) {
            if (!surface.owns(node)) continue;
            int cell=surface.cell(node);
            mask[node] = (byte) 128;
            int wx=tile.baseBlockX()+surface.x(node),wz=tile.baseBlockZ()+surface.z(node);
            int edgeStep=surface.step(node);
            if(surface.ownsAt(wx-1,wz+edgeStep/2) && surface.ownsAt(wx+edgeStep,wz+edgeStep/2)
                    && surface.ownsAt(wx+edgeStep/2,wz-1) && surface.ownsAt(wx+edgeStep/2,wz+edgeStep)) continue;
            if (!exterior(surface, cell)) continue;
            for (int direction = 0; direction < 4; direction++) {
                int nx = direction == 0 ? -1 : direction == 1 ? 1 : 0;
                int nz = direction == 2 ? -1 : direction == 3 ? 1 : 0;
                if(surface.ownsAt(wx+(nx<0?-1:nx>0?edgeStep:edgeStep/2),wz+(nz<0?-1:nz>0?edgeStep:edgeStep/2))) continue;
                int along = nx != 0 ? wz : wx, end = along + edgeStep;
                boolean complete = true;
                int segments = 0;
                while (along < end) {
                    int px = nx == 0 ? along : wx + (nx < 0 ? -1 : edgeStep);
                    int pz = nz == 0 ? along : wz + (nz < 0 ? -1 : edgeStep);
                    var neighbor = index.at(px, pz);
                    if (++segments > 256 || neighbor == null || neighbor.tile() == tile
                            || !exterior(neighbor, PredictionLodSeams.cellAt(neighbor.tile(), px, pz))) {
                        complete = false;
                        break;
                    }
                    int spacing = neighbor.step(neighbor.nodeAt(px,pz));
                    along = Math.min(end, (Math.floorDiv(along, spacing) + 1) * spacing);
                }
                if (complete) mask[node] |= (byte) (1 << direction);
            }
        }
        return mask;
    }

    private static boolean exterior(PredictionLodSeams.Surface surface, int cell) {
        var tile = surface.tile();
        var sample = tile.samples()[PredictionGpuTile.sampleIndexForCell(cell, tile.cellAxis())];
        return sample != null && sample.hasSurface() && !PredictionExteriorColumns.interiorVolume(sample)
                && (PredictionExteriorColumns.profiled(sample) || !PredictionWallEvidence.hasInterior(sample, tile.spacingBlocks())) && tile.mesh().seamMesh().hasTop(cell);
    }

    static boolean replaced(byte[] mask, int cell, int axis, int step, int plane, boolean xNormal) {
        if (mask == null || cell < 0 || cell >= mask.length) return false;
        int origin = (xNormal ? cell % axis : cell / axis) * step;
        int direction = plane == origin ? (xNormal ? 0 : 2)
                : plane == origin + step ? (xNormal ? 1 : 3) : -1;
        return direction >= 0 && (mask[cell] & 1 << direction) != 0;
    }
}
