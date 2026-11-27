package dev.deadstreets.core;

import java.util.UUID;

public final class HordeArrivalHarness {
    private static int checks;
    private static void ok(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static void main(String[] args) {
        long start=1_750_000_000_000L;
        UUID world=UUID.randomUUID(),player=UUID.randomUUID();
        ObservationGuard guard=new ObservationGuard();
        HordeState h=new HordeState(UUID.randomUUID(),world,85,Temperament.AGITATED,350,0,Math.PI,start);
        boolean materialized=false;double physical=Double.NaN;
        // Stationary player, 10-chunk view with 64-block safety buffer.
        for(int t=0;t<=700;t++){
            long now=start+t*1000L;
            guard.observe(player,world,0,0,0,0,10,now);
            double next=h.x()+Math.cos(h.heading())*0.75;
            if(HordeArrivalPolicy.advanceVirtual(guard.protectedPoint(world,next,h.z(),now)))
                h.advance(now,0.75);
            if(!materialized && HordeArrivalPolicy.eligibleMaterialization(Math.abs(h.x()),
                    !guard.protectedPoint(world,h.x()+16,h.z(),now),
                    guard.protectedPoint(world,h.x()-24,h.z(),now))){
                // Model a physical walker spawned in an exterior chunk. This does NOT
                // pretend to emulate Paper's actual pathfinding / chunk packet timing.
                physical=h.x()+16;h.materialized();materialized=true;
            }
            if(materialized && HordeArrivalPolicy.steerPhysical(physical))physical=Math.max(0,physical-0.9);
        }
        ok(materialized,"horde must materialize at an unobserved boundary");
        ok(h.pending()==84,"one materialization consumes one virtual member");
        ok(h.x()<350,"virtual remainder continues migrating after a physical member materializes");
        ok(h.population()==85,"materialization does not alter population");
        ok(h.x()>=224 && h.x()<=260,"virtual anchor remains outside player visibility");
        ok(physical<150,"a steered physical walker can eventually cross the alert threshold");
        ok(HordeArrivalPolicy.eligibleMaterialization(220,true,true),"distance and unseen chunk allow activation");
        ok(!HordeArrivalPolicy.eligibleMaterialization(220,false,true),"observed terrain never eligible");
        ok(!HordeArrivalPolicy.steerPhysical(1200),"navigation remains distance bounded");
        // All 30 players protect the same chunk; no per-player spawn multiplicity.
        for(int i=0;i<30;i++) guard.observe(UUID.randomUUID(),world,500+i*2,500,0,0,10,start+700_000);
        ok(guard.protectedPoint(world,530,500,start+700_000),"30-player shared protection");
        // Retirement removes only virtual population, never the materialized survivor.
        h.retirePending();
        ok(h.population()==1 && h.pending()==0,"retirement preserves one real walker");
        var copy=HordeState.restore(h.save());
        ok(copy.population()==1&&copy.pending()==0,"retired mixed group persists");
        System.out.println("HordeArrivalHarness: "+checks+" checks passed");
    }
}
