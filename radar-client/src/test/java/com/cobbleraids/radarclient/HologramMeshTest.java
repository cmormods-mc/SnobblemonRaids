package com.cobbleraids.radarclient;
import static org.junit.jupiter.api.Assertions.*;
import com.cobbleraids.radar.protocol.RadarHologramPayload;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
class HologramMeshTest {
    @Test void projectionHasAnAirGapAndEmergesFromTheEmitter() {
        assertEquals(0,HologramMesh.projectedHeight(HologramMesh.FLOOR,0));
        assertTrue(HologramMesh.projectedHeight(HologramMesh.FLOOR,1)>.25);
        assertTrue(HologramMesh.projectedHeight(.2f,1)>HologramMesh.projectedHeight(HologramMesh.FLOOR,1));
    }
    @Test void unknownTerrainDoesNotInventGeometry() {
        int[] samples=new int[33*33];Arrays.fill(samples,RadarHologramPayload.UNKNOWN);
        assertTrue(HologramMesh.build(samples).quads().isEmpty());
    }
    @Test void flatTerrainIsRaisedAboveTheCompass() {
        int[] samples=new int[33*33];Arrays.fill(samples,64);
        var mesh=HologramMesh.build(samples);
        assertFalse(mesh.quads().isEmpty());
        assertTrue(mesh.maximumY()>HologramMesh.FLOOR);
        assertEquals(HologramMesh.FLOOR+HologramMesh.RISE,mesh.surfaceAt(0,0),1e-6);
    }
    @Test void hillsHaveBoundedElevationAndOutwardFaces() {
        int[] samples=new int[33*33];
        for(int z=0;z<33;z++) for(int x=0;x<33;x++) samples[z*33+x]=64+(int)Math.max(0,180-Math.hypot(x-16,z-16)*15);
        var mesh=HologramMesh.build(samples);
        assertTrue(mesh.maximumY()>.15);
        assertTrue(mesh.maximumY()<=HologramMesh.FLOOR+HologramMesh.BANDS*HologramMesh.RISE+1e-6);
        assertTrue(mesh.quads().size()<=HologramMesh.CELLS*HologramMesh.CELLS*12);
        for(var face:mesh.quads()) {
            var a=face.a();var b=face.b();var c=face.c();
            double ux=b.x()-a.x(),uy=b.y()-a.y(),uz=b.z()-a.z();
            double vx=c.x()-a.x(),vy=c.y()-a.y(),vz=c.z()-a.z();
            double dot=(uy*vz-uz*vy)*face.normal().x()+(uz*vx-ux*vz)*face.normal().y()+(ux*vy-uy*vx)*face.normal().z();
            assertTrue(dot>0,"Face winding must agree with outward normal");
            for(var v:new HologramMesh.Vertex[]{face.a(),face.b(),face.c(),face.d()}) {
                assertTrue(Math.hypot(v.x(),v.z())<=.465);
                assertTrue(v.y()>=HologramMesh.FLOOR);
            }
        }
    }
    @Test void unknownSamplesLeaveHoles() {
        int[] samples=new int[33*33];Arrays.fill(samples,64);samples[16*33+16]=RadarHologramPayload.UNKNOWN;
        var mesh=HologramMesh.build(samples);
        for(int z=15;z<=16;z++) for(int x=15;x<=16;x++) assertEquals(-1,mesh.bands()[z*HologramMesh.CELLS+x]);
        assertEquals(1,mesh.bands()[14*HologramMesh.CELLS+14]);
    }
}
