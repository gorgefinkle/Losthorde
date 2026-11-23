package dev.deadstreets.core;
import java.util.ArrayList;
import java.util.List;
/** Sidecar marker emitted by the optional Metropolis BUILD exporter. */
public record MetropolisFlag(String worldId,long worldSeed,String buildId,String districtType,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,List<SpawnPoint> interiorPoints,boolean interiorPointsProvided) {
 public record SpawnPoint(int x,int y,int z) {}
 public MetropolisFlag {
  if(worldId==null||worldId.isBlank()||buildId==null||buildId.isBlank()||districtType==null||districtType.isBlank()||minX>maxX||minY>maxY||minZ>maxZ) throw new IllegalArgumentException("Invalid Metropolis flag");
  interiorPoints=interiorPoints==null?List.of():List.copyOf(interiorPoints);
  if(interiorPoints.size()>64)throw new IllegalArgumentException("Too many interior points");
  for(SpawnPoint p:interiorPoints)if(p.x()<minX||p.x()>maxX||p.y()<minY||p.y()>maxY||p.z()<minZ||p.z()>maxZ)throw new IllegalArgumentException("Interior point outside BUILD");
 }
 public MetropolisFlag(String worldId,long worldSeed,String buildId,String districtType,int minX,int minY,int minZ,int maxX,int maxY,int maxZ){this(worldId,worldSeed,buildId,districtType,minX,minY,minZ,maxX,maxY,maxZ,List.of(),false);}
 public MetropolisFlag(String worldId,long worldSeed,String buildId,String districtType,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,List<SpawnPoint> points){this(worldId,worldSeed,buildId,districtType,minX,minY,minZ,maxX,maxY,maxZ,points,true);}
 public static MetropolisFlag parse(String row){
  String[] s=row.split("\\t",-1);if((s.length!=11&&s.length!=12)||!s[0].equals("BUILD"))throw new IllegalArgumentException("Expected BUILD marker");
  List<SpawnPoint> points=new ArrayList<>();
  if(s.length==12&&!s[11].isBlank())for(String token:s[11].split(";")){String[] xyz=token.split(",",-1);if(xyz.length!=3)throw new IllegalArgumentException("Malformed interior point");points.add(new SpawnPoint(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2])));}
  return new MetropolisFlag(s[1],Long.parseLong(s[2]),s[3],s[4],Integer.parseInt(s[5]),Integer.parseInt(s[6]),Integer.parseInt(s[7]),Integer.parseInt(s[8]),Integer.parseInt(s[9]),Integer.parseInt(s[10]),points,s.length==12);
 }
 public String format(){String base="BUILD\t"+worldId+"\t"+worldSeed+"\t"+buildId+"\t"+districtType+"\t"+minX+"\t"+minY+"\t"+minZ+"\t"+maxX+"\t"+maxY+"\t"+maxZ;return !interiorPointsProvided?base:base+"\t"+String.join(";",interiorPoints.stream().map(p->p.x()+","+p.y()+","+p.z()).toList());}
 public int deterministicPopulation(PopulationRoller.Range range){return PopulationRoller.roll(worldSeed,buildId,districtType,range);}
}