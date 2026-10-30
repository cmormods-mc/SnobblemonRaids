package com.cobbleraids.radarclient;

import java.util.ArrayList;
import java.util.List;

/** Cached machined housing; all decoration is real geometry, with small emissive inlays. */
public final class ProjectorDeviceModel {
    public record Point(float x,float y,float z) {}
    public record Face(Point a,Point b,Point c,Point d,Point normal,int color,boolean emissive) {}
    public static final List<Face> FACES = build();
    private ProjectorDeviceModel() {}
    private static List<Face> build() {
        List<Face> faces=new ArrayList<>();
        wall(faces,8,.65f,.72f,-.15f,-.10f,0xFF23303C,false);
        wall(faces,8,.72f,.74f,-.10f,-.055f,0xFF44515F,false);
        wall(faces,8,.74f,.68f,-.055f,.025f,0xFF596675,false);
        // Match the octagonal armor to a genuinely circular opening without gaps or overlaps.
        openingAdapter(faces);
        annulus(faces,96,.575f,.545f,.025f,0xFF7B8A98,false);
        wall(faces,96,.524f,.545f,.005f,.025f,0xFF284A60,true);
        annulus(faces,96,.524f,.515f,.005f,0xFF125F72,true);
        wall(faces,96,.515f,.515f,-.04f,.005f,0xFF131F2C,true);
        disk(faces,96,.515f,-.04f,0xFF0B1523);
        annulus(faces,96,.505f,.49f,-.015f,0xFF2C5362,false);
        annulus(faces,96,.488f,.484f,-.012f,0xFF173647,true);
        disk(faces,64,.125f,-.018f,0xFF0E273A);
        annulus(faces,64,.136f,.126f,-.012f,0xFF6D8191,false);
        annulus(faces,64,.105f,.096f,-.009f,0xFF19778A,true);
        annulus(faces,48,.060f,.050f,-.006f,0xFF217D8C,true);
        disk(faces,48,.043f,-.005f,0xFF70B0BA);
        // Exposed ribs, docking lugs and small amber/cyan status clusters.
        for(int side:new int[]{-1,1}) {
            box(faces,side*.57f-.075f,-.105f,-.23f,side*.57f+.075f,.010f,.23f,0xFF27313C,false);
            for(int i=0;i<9;i++) {
                float z=-.19f+i*.045f;
                box(faces,side*.59f-.055f,-.065f,z,side*.59f+.055f,-.005f,z+.014f,0xFF6D7C88,false);
            }
            box(faces,side*.27f-.028f,.025f,.56f,side*.27f+.028f,.225f,.63f,0xFF536574,false);
            box(faces,side*.27f-.014f,.045f,.627f,side*.27f+.014f,.205f,.635f,0xFF154B61,true);
            box(faces,side*.34f-.045f,.027f,.445f,side*.34f+.045f,.048f,.49f,0xFF161E2A,false);
            for(int i=0;i<4;i++) box(faces,side*.34f-.032f+i*.018f,.049f,.458f,
                    side*.34f-.021f+i*.018f,.052f,.479f,i==3?0xFF885522:0xFF166C7C,true);
        }
        // Captive hex bolts and recessed drive slots on the armored perimeter.
        for(int i=0;i<8;i++) {
            double angle=(i+.5)*Math.PI/4;
            float x=(float)Math.sin(angle)*.625f,z=(float)Math.cos(angle)*.625f;
            discAt(faces,x,z,.020f,.031f,6,0xFF111B25);
            discAt(faces,x,z,.015f,.038f,6,0xFF9AA4AA);
            box(faces,x-.009f,.0385f,z-.0025f,x+.009f,.040f,z+.0025f,0xFF17232E,false);
        }
        for(int i=0;i<72;i++) {
            double a=i*Math.PI/36;
            float x=(float)Math.sin(a),z=(float)Math.cos(a);
            float inner=i%6==0?.455f:.470f,outer=.482f,w=i%6==0?.0035f:.0015f;
            strip(faces,x*inner,z*inner,x*outer,z*outer,-.010f,w,i%6==0?0xFF78B5BF:0xFF315467,false);
        }
        // Radial engraved traces and optical emitter lenses inside the recessed well.
        for(int i=0;i<12;i++) {
            double a=i*Math.PI/6;
            float x=(float)Math.sin(a),z=(float)Math.cos(a);
            strip(faces,x*.15f,z*.15f,x*.41f,z*.41f,-.025f,.0015f,0xFF274556,false);
            discAt(faces,x*.37f,z*.37f,.016f,-.022f,12,0xFF244555);
            discAt(faces,x*.37f,z*.37f,.009f,-.019f,12,0xFF126878);
        }
        // Substantial front display bracket rather than text painted on the dial.
        box(faces,-.40f,.135f,.56f,.40f,.215f,.69f,0xFF14212E,false);
        box(faces,-.42f,.125f,.59f,.42f,.145f,.68f,0xFF566575,false);
        return List.copyOf(faces);
    }
    private static void wall(List<Face> f,int segments,float lower,float upper,float bottom,float top,int color,boolean inward) {
        double slope=(lower-upper)/(top-bottom),length=Math.sqrt(1+slope*slope),sign=inward?-1:1;
        for(int i=0;i<segments;i++) {
            double a=i*2*Math.PI/segments,b=(i+1)*2*Math.PI/segments,m=(a+b)/2;
            Point p0=p(Math.sin(a)*lower,bottom,Math.cos(a)*lower),p1=p(Math.sin(b)*lower,bottom,Math.cos(b)*lower);
            Point p2=p(Math.sin(b)*upper,top,Math.cos(b)*upper),p3=p(Math.sin(a)*upper,top,Math.cos(a)*upper);
            Point normal=p(sign*Math.sin(m)/length,sign*slope/length,sign*Math.cos(m)/length);
            if(inward) face(f,p0,p3,p2,p1,normal,color,false);
            else face(f,p0,p1,p2,p3,normal,color,false);
        }
    }
    private static void openingAdapter(List<Face> f) {
        for(int i=0;i<96;i++) {
            double a=i*Math.PI/48,b=(i+1)*Math.PI/48;
            double ar=octagonRadius(a),br=octagonRadius(b);
            face(f,p(Math.sin(a)*ar,.025,Math.cos(a)*ar),p(Math.sin(b)*br,.025,Math.cos(b)*br),
                    p(Math.sin(b)*.575,.025,Math.cos(b)*.575),p(Math.sin(a)*.575,.025,Math.cos(a)*.575),
                    p(0,1,0),0xFF3A4857,false);
        }
    }
    private static double octagonRadius(double angle) {
        double sector=angle%(Math.PI/4)-Math.PI/8;
        return .68*Math.cos(Math.PI/8)/Math.cos(sector);
    }
    private static void annulus(List<Face> f,int n,float outer,float inner,float y,int color,boolean glow) {
        for(int i=0;i<n;i++) {
            double a=i*2*Math.PI/n,b=(i+1)*2*Math.PI/n;
            face(f,p(Math.sin(a)*outer,y,Math.cos(a)*outer),p(Math.sin(b)*outer,y,Math.cos(b)*outer),
                    p(Math.sin(b)*inner,y,Math.cos(b)*inner),p(Math.sin(a)*inner,y,Math.cos(a)*inner),p(0,1,0),color,glow);
        }
    }
    private static void disk(List<Face> f,int n,float radius,float y,int color) { discAt(f,0,0,radius,y,n,color); }
    private static void discAt(List<Face> f,float x,float z,float radius,float y,int n,int color) {
        for(int i=0;i<n;i++) {
            double a=i*2*Math.PI/n,b=(i+1)*2*Math.PI/n;
            Point center=p(x,y,z);
            face(f,center,p(x+Math.sin(a)*radius,y,z+Math.cos(a)*radius),
                    p(x+Math.sin(b)*radius,y,z+Math.cos(b)*radius),center,p(0,1,0),color,false);
        }
    }
    private static void strip(List<Face> f,float ax,float az,float bx,float bz,float y,float w,int color,boolean glow) {
        float len=(float)Math.hypot(bx-ax,bz-az),ox=-(bz-az)/len*w,oz=(bx-ax)/len*w;
        face(f,p(ax+ox,y,az+oz),p(bx+ox,y,bz+oz),p(bx-ox,y,bz-oz),p(ax-ox,y,az-oz),p(0,1,0),color,glow);
    }
    private static void box(List<Face> f,float x0,float y0,float z0,float x1,float y1,float z1,int color,boolean glow) {
        face(f,p(x0,y1,z0),p(x0,y1,z1),p(x1,y1,z1),p(x1,y1,z0),p(0,1,0),color,glow);
        face(f,p(x0,y0,z0),p(x1,y0,z0),p(x1,y0,z1),p(x0,y0,z1),p(0,-1,0),color,glow);
        face(f,p(x0,y0,z0),p(x0,y1,z0),p(x1,y1,z0),p(x1,y0,z0),p(0,0,-1),color,glow);
        face(f,p(x1,y0,z1),p(x1,y1,z1),p(x0,y1,z1),p(x0,y0,z1),p(0,0,1),color,glow);
        face(f,p(x0,y0,z1),p(x0,y1,z1),p(x0,y1,z0),p(x0,y0,z0),p(-1,0,0),color,glow);
        face(f,p(x1,y0,z0),p(x1,y1,z0),p(x1,y1,z1),p(x1,y0,z1),p(1,0,0),color,glow);
    }
    private static Point p(double x,double y,double z) { return new Point((float)x,(float)y,(float)z); }
    private static void face(List<Face> f,Point a,Point b,Point c,Point d,Point n,int color,boolean glow) {
        f.add(new Face(a,b,c,d,n,color,glow));
    }
}
