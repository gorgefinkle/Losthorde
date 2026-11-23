package dev.deadstreets.core;
/** Eight-way Minecraft compass bearing from a player to a source position. */
public final class CompassDirection {
 private CompassDirection(){}
 public static String from(double playerX,double playerZ,double sourceX,double sourceZ){
  if(!Double.isFinite(playerX)||!Double.isFinite(playerZ)||!Double.isFinite(sourceX)||!Double.isFinite(sourceZ))return "Unknown";
  double dx=sourceX-playerX,dz=sourceZ-playerZ;
  if(Math.abs(dx)+Math.abs(dz)<1.0e-6)return "Nearby";
  double angle=Math.atan2(dx,-dz);
  int sector=(int)Math.floor((angle+Math.PI/8.0)/(Math.PI/4.0));
  return switch(Math.floorMod(sector,8)){
   case 0->"North";case 1->"North-East";case 2->"East";case 3->"South-East";
   case 4->"South";case 5->"South-West";case 6->"West";default->"North-West";
  };
 }
}