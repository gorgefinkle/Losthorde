package dev.deadstreets.core;

import java.util.UUID;

/** Pure lifecycle and sound unit probes, no server required. */
public final class HordeStateHarness {
    private static int checks;
    private static void check(boolean yes) {checks++;if(!yes)throw new AssertionError("Failed horde probe #"+checks);}
    public static void main(String[] args) {
        UUID id=UUID.randomUUID(),world=UUID.randomUUID();
        HordeState docile=new HordeState(id,world,20,Temperament.DOCILE,0,0,0,1_000L);
        check(!docile.hear(30,0,1,2_000L));
        check(docile.hear(24,0,1,2_000L));
        check(!docile.hear(75,0,2.5,2_000L));
        docile.advance(3_000L,1.0);
        check(docile.x()>0 && docile.x()<=2);
        String saved=docile.save();
        HordeState restored=HordeState.restore(saved);
        check(restored.id().equals(docile.id()));
        check(restored.worldId().equals(world));
        check(restored.population()==20 && restored.pending()==20);
        check(restored.temperament()==Temperament.DOCILE);
        double pos=restored.x();
        restored.advance(10_000_000L,0.75);
        check(restored.x()-pos<3.76); // downtime is capped to five simulated seconds
        restored.materialized();restored.materialized();
        check(restored.pending()==18 && restored.population()==20);
        restored.died();
        check(restored.population()==19);
        check(HordeState.restore(restored.save()).pending()==18);
        HordeState frenzied=new HordeState(UUID.randomUUID(),world,5,Temperament.FRENZIED,0,0,0,0);
        check(frenzied.hear(125,0,1,1));
        check(!frenzied.hear(130,0,1,1));
        check(frenzied.hear(300,0,3,1));
        check(!frenzied.hear(Double.NaN,0,2,1));
        for(int n=0;n<5;n++) {frenzied.materialized();frenzied.died();}
        check(frenzied.pending()==0 && frenzied.empty());
        boolean rejected=false;
        try {frenzied.materialized();} catch(IllegalStateException ex){rejected=true;}
        check(rejected);
        for(String bad:new String[]{"","a|b","x|y|10|11|FRENZIED|0|0|0|0|||0"}) {
            rejected=false;try{HordeState.restore(bad);}catch(RuntimeException ex){rejected=true;}
            check(rejected);
        }
        System.out.println("HordeStateHarness: "+checks+" checks passed");
    }
}
