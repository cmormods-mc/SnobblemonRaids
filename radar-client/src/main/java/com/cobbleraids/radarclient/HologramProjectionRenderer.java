package com.cobbleraids.radarclient;

import com.cobbleraids.radar.protocol.RadarHologramPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Standard entity vertices only: no core-shader override or Iris-specific GL calls. */
public final class HologramProjectionRenderer {
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath("cobbleraids_radar", "textures/white.png");
    private static final ResourceLocation METAL_TEXTURE = ResourceLocation.fromNamespaceAndPath("cobbleraids_radar", "textures/technical_metal.png");
    private static final int DARK = 0xFF101D29, METAL = 0xFF263E4C, CYAN = 0xFF65EFE1, ORANGE = 0xFFFF9D35;
    private static final MultiBufferSource.BufferSource DRAW = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 20));
    private HologramProjectionRenderer() {}

    public static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !client.options.getCameraType().isFirstPerson()) return;
        AbstractClientPlayer player = client.player;
        if (!RadarHologramClient.matches(player.getMainHandItem()) && !RadarHologramClient.matches(player.getOffhandItem())) return;
        PoseStack poses = context.matrixStack();
        MultiBufferSource buffers = DRAW;
        if (poses == null || buffers == null) return;
        RadarHologramPayload frame = RadarHologramClient.frame();
        HologramMesh.Mesh mesh = RadarHologramClient.mesh();
        var forward = context.camera().getLookVector();
        poses.pushPose();
        // Coordinates are camera-relative. The compass lies in the world X/Z plane and hovers
        // below the sightline; it has no arm transform, fixed tilt, equip animation or swing.
        poses.translate(forward.x() * 1.65, forward.y() * 1.65 - .40, forward.z() * 1.65);
        poses.scale(.90f, .90f, .90f);
        device(poses, buffers, context.camera().getYRot());
        float emergence = RadarHologramClient.emergence();
        // Tilt only the projection toward the viewer; the physical compass stays level.
        // Rotating around the emitter also keeps the shortened rays attached to its lens.
        poses.pushPose();
        float yaw = context.camera().getYRot();
        poses.mulPose(Axis.YP.rotationDegrees(-yaw));
        poses.mulPose(Axis.XP.rotationDegrees(-12));
        poses.mulPose(Axis.YP.rotationDegrees(yaw));
        // Keep broad surfaces and the projection cone out of the emissive pass: otherwise
        // bloom can wash out the wireframe. Only the brighter wire colors are emissive.
        VertexConsumer translucent = buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        projection(poses, translucent, emergence);
        for (HologramMesh.Quad quad : mesh.quads()) {
            if ((quad.color() >>> 24) < 192) drawMeshQuad(poses, translucent, quad, emergence);
        }
        VertexConsumer glow = buffers.getBuffer(RenderType.entityTranslucentEmissive(WHITE));
        for (HologramMesh.Quad quad : mesh.quads()) {
            if ((quad.color() >>> 24) >= 192) drawMeshQuad(poses, glow, quad, emergence);
        }
        float px = (float) ((player.getX() - frame.centerX()) / 512), pz = (float) ((player.getZ() - frame.centerZ()) / 512);
        float guideY = HologramMesh.projectedHeight(mesh.maximumY() + .025f, emergence);
        if (frame.hasTarget()) {
            float tx = (float) ((frame.targetX() - frame.centerX()) / 512), tz = (float) ((frame.targetZ() - frame.centerZ()) / 512);
            // Ray-circle intersection preserves bearing from the actual player's position.
            if (Math.hypot(tx,tz) > .43) {
                float dx = tx-px, dz = tz-pz;
                double a = dx*dx+dz*dz, b = 2*(px*dx+pz*dz), c = px*px+pz*pz-.43*.43;
                double t = (-b+Math.sqrt(b*b-4*a*c))/(2*a);
                tx = px+(float)t*dx; tz = pz+(float)t*dz;
            }
            float distance = (float) Math.hypot(tx-px,tz-pz);
            int dots = Math.max(1,(int)(distance/.024));
            for(int i=0;i<=dots;i++) {
                float t=(float)i/dots, x=px+(tx-px)*t, z=pz+(tz-pz)*t;
                flatQuad(poses,glow,x-.003f,z-.003f,x+.003f,z+.003f,guideY,CYAN);
            }
            float beaconY = guideY+.07f;
            float ground = HologramMesh.projectedHeight(mesh.surfaceAt(tx,tz), emergence);
            quad(poses,glow,tx-.002f,ground,tz,tx+.002f,ground,tz,
                    tx+.002f,beaconY,tz,tx-.002f,beaconY,tz,ORANGE,0,0,1);
            diamond(poses,glow,tx,beaconY,tz,.023f,ORANGE);
            markerLabel(poses,buffers,context,"RAID",tx,beaconY+.04f,tz,ORANGE);
            glow=buffers.getBuffer(RenderType.entityTranslucentEmissive(WHITE));
        }
        float angle = (float)Math.toRadians(player.getYRot()), dx = -(float)Math.sin(angle), dz = (float)Math.cos(angle);
        float ax=px+dx*.028f, az=pz+dz*.028f, bx=px-dx*.015f-dz*.018f, bz=pz-dz*.015f+dx*.018f;
        float cx=px-dx*.015f+dz*.018f, cz=pz-dz*.015f-dx*.018f;
        quad(poses,glow,ax,guideY+.005f,az,cx,guideY+.005f,cz,bx,guideY+.005f,bz,bx,guideY+.005f,bz,0xFFFFFFFF,0,1,0);
        markerLabel(poses,buffers,context,"YOU",px,guideY+.055f,pz,0xFFFFFFFF);
        poses.popPose();
        label(poses,buffers,"N",-.012f,.025f,-.52f,CYAN,.0035f);
        label(poses,buffers,"S",-.012f,.025f,.50f,CYAN,.0035f);
        label(poses,buffers,"W",-.53f,.025f,-.015f,CYAN,.0035f);
        label(poses,buffers,"E",.50f,.025f,-.015f,CYAN,.0035f);
        readout(poses, buffers, context, frame, player);
        poses.popPose();
        DRAW.endBatch();
    }

    private static void drawMeshQuad(PoseStack poses, VertexConsumer buffer, HologramMesh.Quad quad, float emergence) {
        vertex(poses,buffer,quad.a().x(),HologramMesh.projectedHeight(quad.a().y(),emergence),quad.a().z(),quad.color(),quad.normal().x(),quad.normal().y(),quad.normal().z());
        vertex(poses,buffer,quad.b().x(),HologramMesh.projectedHeight(quad.b().y(),emergence),quad.b().z(),quad.color(),quad.normal().x(),quad.normal().y(),quad.normal().z());
        vertex(poses,buffer,quad.c().x(),HologramMesh.projectedHeight(quad.c().y(),emergence),quad.c().z(),quad.color(),quad.normal().x(),quad.normal().y(),quad.normal().z());
        vertex(poses,buffer,quad.d().x(),HologramMesh.projectedHeight(quad.d().y(),emergence),quad.d().z(),quad.color(),quad.normal().x(),quad.normal().y(),quad.normal().z());
    }

    /** A sparse translucent projection cone connects the emitter to the suspended terrain. */
    private static void projection(PoseStack poses, VertexConsumer buffer, float emergence) {
        float floor = HologramMesh.projectedHeight(HologramMesh.FLOOR, emergence);
        // A subdued suspended backing keeps the fine topology legible against daylight.
        for(int i=0;i<64;i++) {
            double a=i*Math.PI/32,b=(i+1)*Math.PI/32;
            quad(poses,buffer,0,floor-.003f,0,(float)Math.sin(a)*.465f,floor-.003f,(float)Math.cos(a)*.465f,
                    (float)Math.sin(b)*.465f,floor-.003f,(float)Math.cos(b)*.465f,0,floor-.003f,0,0x66051323,0,1,0);
        }
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6, b = (i + 1) * Math.PI / 6;
            float ax = (float) Math.sin(a) * .44f, az = (float) Math.cos(a) * .44f;
            float bx = (float) Math.sin(b) * .44f, bz = (float) Math.cos(b) * .44f;
            vertex(poses,buffer,0,.015f,0,0x704AF3ED,0,1,0);
            vertex(poses,buffer,ax,floor,az,0x084AF3ED,0,1,0);
            vertex(poses,buffer,bx,floor,bz,0x084AF3ED,0,1,0);
            vertex(poses,buffer,0,.015f,0,0x704AF3ED,0,1,0);
            strip(poses,buffer,ax,az,bx,bz,floor,.001f,0x885AF6E9);
            // Thin visible rays sit inside the softer projection cone.
            quad(poses,buffer,-.002f,.018f,0,ax-.001f,floor,az,
                    ax+.001f,floor,az,.002f,.018f,0,0x365AF6E9,0,0,1);
        }
        flatQuad(poses,buffer,-.035f,-.035f,.035f,.035f,.016f,0xE0A2FFF2);
    }

    private static void device(PoseStack poses, MultiBufferSource buffers, float yaw) {
        poses.pushPose();
        poses.mulPose(Axis.YP.rotationDegrees(180-yaw));
        for (boolean emissive : new boolean[]{false,true}) {
            VertexConsumer buffer = buffers.getBuffer(emissive ? RenderType.entityTranslucentEmissive(WHITE) : RenderType.entitySolid(METAL_TEXTURE));
            for (var face : ProjectorDeviceModel.FACES) if (face.emissive()==emissive) {
                for (var v : new ProjectorDeviceModel.Point[]{face.a(),face.b(),face.c(),face.d()}) {
                    var n=face.normal();
                    float u=.5f+v.x()*.6f, t=.5f+v.z()*.6f;
                    if(Math.abs(n.y())<.5f) {u=.5f+(Math.abs(n.x())>.5f?v.z():v.x())*.6f;t=.5f+v.y()*2;}
                    buffer.addVertex(poses.last(),v.x(),v.y(),v.z()).setColor(face.color()).setUv(emissive?.5f:u,emissive?.5f:t)
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                            .setNormal(poses.last(),n.x(),n.y(),n.z());
                }
            }
        }
        poses.popPose();
    }
    private static void markerLabel(PoseStack poses,MultiBufferSource buffers,WorldRenderContext context,
                                    String value,float x,float y,float z,int color) {
        poses.pushPose();poses.translate(x,y,z);poses.mulPose(context.camera().rotation());poses.scale(.004f,-.004f,.004f);
        Minecraft.getInstance().font.drawInBatch(value,-Minecraft.getInstance().font.width(value)/2f,-8,color,true,
                poses.last().pose(),buffers,Font.DisplayMode.NORMAL,0,LightTexture.FULL_BRIGHT);
        poses.popPose();
    }
    /** A high-contrast instrument display physically mounted above the front bracket. */
    private static void readout(PoseStack poses, MultiBufferSource buffers, WorldRenderContext context,
                                RadarHologramPayload frame, AbstractClientPlayer player) {
        var look=context.camera().getLookVector();
        float length=(float)Math.hypot(look.x(),look.z());
        float x=length>.001f?-look.x()/length:0, z=length>.001f?-look.z()/length:1;
        poses.pushPose();poses.translate(x*.78f,.27f,z*.78f);
        poses.mulPose(context.camera().rotation());poses.scale(.0032f,-.0032f,.0032f);
        VertexConsumer background=buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        quad(poses,background,-126,-29,0,126,-29,0,126,39,0,-126,39,0,0xFF536675,0,0,1);
        quad(poses,background,-123,-26,.1f,123,-26,.1f,123,36,.1f,-123,36,.1f,0xFF07121D,0,0,1);
        quad(poses,background,-123,-26,.2f,-119,-26,.2f,-119,36,.2f,-123,36,.2f,CYAN,0,0,1);
        text(poses,buffers,"COBBLERAIDS  /  RADAR",-111,-19,.8f,0xFF8DB1C2);
        String name=frame.hasTarget()?frame.species().toUpperCase(java.util.Locale.ROOT):"NO RAID SIGNAL";
        if(name.length()>19) name=name.substring(0,18)+".";
        text(poses,buffers,name,-111,-5,1.4f,0xFFF0FAFF);
        String distance=frame.hasTarget()?Math.round(Math.hypot(frame.targetX()-player.getX(),frame.targetZ()-player.getZ()))+"m":"--";
        text(poses,buffers,distance,110-Minecraft.getInstance().font.width(distance)*1.8f,-7,1.8f,ORANGE);
        text(poses,buffers,frame.hasTarget()?frame.tier().toUpperCase(java.util.Locale.ROOT):"SCANNING",-111,16,.95f,ORANGE);
        text(poses,buffers,frame.lastKnown()?"LAST KNOWN":"TRACKING",-35,17,.8f,CYAN);
        double bearing=(Math.toDegrees(Math.atan2(frame.targetX()-player.getX(),-(frame.targetZ()-player.getZ())))+360)%360;
        String bearingText=frame.hasTarget()?String.format(java.util.Locale.ROOT,"%03d DEG",Math.round(bearing)%360):"STANDBY";
        text(poses,buffers,bearingText,110-Minecraft.getInstance().font.width(bearingText)*.8f,17,.8f,0xFFA8C0CF);
        poses.popPose();
    }
    private static void text(PoseStack poses,MultiBufferSource buffers,String value,float x,float y,float size,int color) {
        poses.pushPose();poses.translate(x,y,.3);poses.scale(size,size,size);
        Minecraft.getInstance().font.drawInBatch(value,0,0,color,false,poses.last().pose(),buffers,Font.DisplayMode.NORMAL,0,LightTexture.FULL_BRIGHT);
        poses.popPose();
    }
    private static void diamond(PoseStack poses,VertexConsumer buffer,float x,float y,float z,float radius,int color) {
        float w=.003f;
        // Four strips in the horizontal plane, clearly separated above the terrain.
        strip(poses,buffer,x,z-radius,x+radius,z,y,w,color);
        strip(poses,buffer,x+radius,z,x,z+radius,y,w,color);
        strip(poses,buffer,x,z+radius,x-radius,z,y,w,color);
        strip(poses,buffer,x-radius,z,x,z-radius,y,w,color);
    }
    private static void strip(PoseStack poses,VertexConsumer buffer,float ax,float az,float bx,float bz,float y,float width,int color) {
        float length=(float)Math.hypot(bx-ax,bz-az),ox=-(bz-az)/length*width,oz=(bx-ax)/length*width;
        quad(poses,buffer,ax+ox,y,az+oz,bx+ox,y,bz+oz,bx-ox,y,bz-oz,ax-ox,y,az-oz,color,0,1,0);
    }
    private static void flatQuad(PoseStack poses,VertexConsumer buffer,float x0,float z0,float x1,float z1,float y,int color) {
        quad(poses,buffer,x0,y,z0,x0,y,z1,x1,y,z1,x1,y,z0,color,0,1,0);
    }
    private static void quad(PoseStack poses,VertexConsumer buffer,float ax,float ay,float az,float bx,float by,float bz,
                             float cx,float cy,float cz,float dx,float dy,float dz,int color,float nx,float ny,float nz) {
        vertex(poses,buffer,ax,ay,az,color,nx,ny,nz);vertex(poses,buffer,bx,by,bz,color,nx,ny,nz);
        vertex(poses,buffer,cx,cy,cz,color,nx,ny,nz);vertex(poses,buffer,dx,dy,dz,color,nx,ny,nz);
    }
    private static void vertex(PoseStack poses,VertexConsumer buffer,float x,float y,float z,int color,float nx,float ny,float nz) {
        buffer.addVertex(poses.last(),x,y,z).setColor(color).setUv(.5f,.5f)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(poses.last(),nx,ny,nz);
    }
    private static void label(PoseStack poses,MultiBufferSource buffers,String value,float x,float y,float z,int color,float scale) {
        poses.pushPose();poses.translate(x,y,z);poses.mulPose(Axis.XP.rotationDegrees(90));poses.scale(scale,scale,scale);
        Minecraft.getInstance().font.drawInBatch(value,0,0,color,false,poses.last().pose(),buffers,Font.DisplayMode.NORMAL,0,LightTexture.FULL_BRIGHT);
        poses.popPose();
    }
}
