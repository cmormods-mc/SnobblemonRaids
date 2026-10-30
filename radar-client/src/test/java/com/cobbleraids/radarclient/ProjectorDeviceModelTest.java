package com.cobbleraids.radarclient;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ProjectorDeviceModelTest {
    @Test void housingHasBoundedLayeredGeometryAndSeparateLitInlays() {
        var faces=ProjectorDeviceModel.FACES;
        assertTrue(faces.size()>1000);
        assertTrue(faces.size()<5000);
        assertTrue(faces.stream().anyMatch(ProjectorDeviceModel.Face::emissive));
        assertTrue(faces.stream().anyMatch(f -> !f.emissive()));
        float low=Float.MAX_VALUE,high=-Float.MAX_VALUE;
        for(var face:faces) for(var v:new ProjectorDeviceModel.Point[]{face.a(),face.b(),face.c(),face.d()}) {
            assertTrue(Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z()));
            assertTrue(Math.abs(v.x())<=.75f && Math.abs(v.z())<=.75f);
            low=Math.min(low,v.y());high=Math.max(high,v.y());
        }
        assertTrue(low<-.14f && high>.20f,"Housing must have physical depth and raised display mounts");
        assertThrows(UnsupportedOperationException.class, () -> faces.clear());
    }
    @Test void surfacesFaceTheirLightingNormalsAndRecessFacesInward() {
        int inside=0;
        for(var face:ProjectorDeviceModel.FACES) {
            var a=face.a();var b=face.b();var c=face.c();var n=face.normal();
            double ux=b.x()-a.x(),uy=b.y()-a.y(),uz=b.z()-a.z();
            double vx=c.x()-a.x(),vy=c.y()-a.y(),vz=c.z()-a.z();
            double dot=(uy*vz-uz*vy)*n.x()+(uz*vx-ux*vz)*n.y()+(ux*vy-uy*vx)*n.z();
            assertTrue(dot>0,"Backface winding must match the visible surface normal");
            assertEquals(1,Math.sqrt(n.x()*n.x()+n.y()*n.y()+n.z()*n.z()),1e-5);
            if(a.x()*n.x()+a.z()*n.z()<-.1 && Math.hypot(a.x(),a.z())<.55) inside++;
        }
        assertTrue(inside>=192,"Both the beveled lip and recessed liner must face into the well");
    }
    @Test void circularCollarMeetsTheArmorWithoutUncoveredEdges() {
        var adapter=ProjectorDeviceModel.FACES.stream().filter(f -> f.color()==0xFF3A4857).toList();
        assertEquals(96,adapter.size());
        for(int i=0;i<adapter.size();i++) {
            var face=adapter.get(i);var next=adapter.get((i+1)%adapter.size());
            assertEquals(face.b().x(),next.a().x(),1e-6);assertEquals(face.b().z(),next.a().z(),1e-6);
            assertEquals(face.c().x(),next.d().x(),1e-6);assertEquals(face.c().z(),next.d().z(),1e-6);
            assertEquals(.575,Math.hypot(face.c().x(),face.c().z()),1e-6);
            assertTrue(Math.hypot(face.a().x(),face.a().z())>.62);
        }
    }
}
