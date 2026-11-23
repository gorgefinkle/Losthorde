package dev.deadstreets.core;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
/** Pure, immutable per-build placement planner. */
public final class PopulationPlan {
 private PopulationPlan(){}
 public record Candidate(String buildId,int index,int chunkX,int chunkZ,int x,int y,int z,long salt){}
 public static List<Candidate> inChunk(MetropolisFlag flag,PopulationRoller.Range range,int chunkX,int chunkZ,int maxPerBuild){
  if(maxPerBuild<1)throw new IllegalArgumentException("maxPerBuild must be positive");int population=flag.deterministicPopulation(range);
  if(population<0||population>maxPerBuild)throw new IllegalArgumentException("population exceeds per-build safety limit");
  List<Candidate> out=new ArrayList<>();long seed=mix64(flag.worldSeed()^hash(flag.worldId()+":"+flag.buildId()));
  if(!flag.interiorPoints().isEmpty()){List<MetropolisFlag.SpawnPoint> points=flag.interiorPoints();int start=(int)Math.floorMod(seed,points.size()); for(int i=0;i<Math.min(population,points.size());i++){long salt=mix64(seed+i*0x9e3779b97f4a7c15L);var p=points.get((start+i)%points.size());int cx=Math.floorDiv(p.x(),16),cz=Math.floorDiv(p.z(),16);if(cx==chunkX&&cz==chunkZ)out.add(new Candidate(flag.buildId(),i,cx,cz,p.x(),p.y(),p.z(),salt));}return List.copyOf(out);}
  int west=Math.floorDiv(flag.minX(),16),east=Math.floorDiv(flag.maxX(),16),north=Math.floorDiv(flag.minZ(),16),south=Math.floorDiv(flag.maxZ(),16);long width=(long)east-west+1,depth=(long)south-north+1;
  if(width<1||depth<1||width>32||depth>32)throw new IllegalArgumentException("build footprint spans more than 32 chunks");if(chunkX<west||chunkX>east||chunkZ<north||chunkZ>south)return List.of();
  for(int i=0;i<population;i++){long a=mix64(seed+i*0x9e3779b97f4a7c15L);int cx=west+(int)Math.floorMod(a,width),cz=north+(int)Math.floorMod(mix64(a),depth);if(cx!=chunkX||cz!=chunkZ)continue;int minX=Math.max(flag.minX(),cx*16),maxX=Math.min(flag.maxX(),cx*16+15),minZ=Math.max(flag.minZ(),cz*16),maxZ=Math.min(flag.maxZ(),cz*16+15);long b=mix64(a^0xd1b54a32d192ed03L);int x=minX+(int)Math.floorMod(b,(long)maxX-minX+1),z=minZ+(int)Math.floorMod(mix64(b),(long)maxZ-minZ+1);out.add(new Candidate(flag.buildId(),i,cx,cz,x,-1,z,b));}
  return List.copyOf(out);
 }
 public static String activationKey(MetropolisFlag f){return "build_"+Long.toUnsignedString(hash(f.worldId()+":"+f.worldSeed()+":"+f.buildId()+":"+f.districtType()),36);}
 private static long hash(String text){long r=0xcbf29ce484222325L;for(byte b:text.getBytes(StandardCharsets.UTF_8)){r^=b&255;r*=0x100000001b3L;}return r;}
 public static long mix64(long v){v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;return v^(v>>>31);}
}