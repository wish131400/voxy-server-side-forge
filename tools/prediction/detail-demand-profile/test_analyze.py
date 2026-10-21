import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location("demand_analyze",Path(__file__).with_name("analyze.py"))
analyze=importlib.util.module_from_spec(spec); spec.loader.exec_module(analyze)


class AnalysisTests(unittest.TestCase):
    def rows(self):
        rows=[]
        for i in range(14):
            hist=[0]*502;hist[10]=100*i
            values={k:0 for k in ("sourceQuads","terrainQuads","displayQuads","selectedQuads","noTerrainVariantTiles","mixedOwnershipTiles","activeMorphTiles")}
            row=dict(schema=1,run="test",session=0,dimension="overworld",gameVersion="1.21.1",monotonicNanos=(i+1)*10**9,
                     frame=i*100,camera=[64,80,128],modelView=[1]*16,projection=[1]*16,viewport=[256,256],detail="normal",
                     horizonBlocks=8192,surfaceDistanceBlocks=256,iris=False,scoping=False,menuOpen=False,menuDuringInterval=False,droppedRows=0,
                     frameCounters=dict(frames=i*100,durationNanos=i*10**9,over33ms=0,over50ms=0,histogramMillis=hist),
                     generation=dict(contentVersion=1,built=i,failed=0,supersededBuilds=0,visibleCpuNanos=i*100,backgroundCpuNanos=0),
                     timings=dict(OPAQUE=dict(cpuNanos=i*200,cpuSamples=i,gpuNanos=i*2000000,gpuSamples=i)),
                     meshUploads=i,preparedPlans=i,reusedPlans=i,submittedQuads=i*1000,
                     opaque=values,water=values,gpuResidency=dict(liveBytes=100,committedBytes=200,sourceQuads=100,displayQuads=10))
            rows.append(row)
        return rows

    def parse(self,rows):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/"profile.jsonl"
            p.write_text("\n".join(json.dumps(row) for row in rows),encoding="utf-8")
            return analyze.intervals(p)[0]

    def test_weighted_frame_histogram_and_timing_deltas(self):
        values=self.parse(self.rows()); stats=analyze.summarize(values)
        self.assertEqual(100,stats["fps"]);self.assertEqual(10,stats["p99MsUpper"])
        self.assertEqual(13,stats["timings"]["OPAQUE"]["gpu"]["samples"])
        self.assertEqual(2,stats["timings"]["OPAQUE"]["gpu"]["meanMs"])
        self.assertTrue(analyze.compare(values,values)["normal-static"]["comparableFixedView"])

    def test_menu_dropped_rows_and_session_resets_excluded(self):
        rows=self.rows();rows[2]["menuDuringInterval"]=True;rows[3]["droppedRows"]=1
        rows[8]["session"]=2
        self.assertEqual(8,len(self.parse(rows)))

    def test_different_viewport_or_camera_cannot_claim_fixed_view_gain(self):
        old=self.parse(self.rows());rows=self.rows()
        for row in rows:row["viewport"]=[1920,1080]
        new=self.parse(rows)
        self.assertFalse(analyze.compare(old,new)["normal-static"]["comparableFixedView"])
        rows=self.rows()
        for row in rows:row["camera"][0]+=10
        self.assertFalse(analyze.compare(old,self.parse(rows))["normal-static"]["comparableFixedView"])

    def test_tail_frames_use_counter_differences_not_overlapping_window_averages(self):
        rows=self.rows()
        for row in rows[4:]:
            row["frameCounters"]["histogramMillis"][10]-=2
            row["frameCounters"]["histogramMillis"][100]=2
            row["frameCounters"]["durationNanos"]+=180000000
            row["frameCounters"]["over50ms"]=2
        stats=analyze.summarize(self.parse(rows))
        self.assertEqual(2,stats["over50ms"]);self.assertEqual(10,stats["p99MsUpper"])


if __name__=="__main__":unittest.main()
