package dev.deadstreets.core;

import java.util.UUID;

/** Deterministic observation tests: multiple users, flight, teleports and recent-area hysteresis. */
public final class ObservationGuardHarness {
    private static int checks;
    private static void check(boolean ok) {if(!ok) throw new AssertionError("Observation guard check "+(++checks)); checks++;}
    public static void main(String[] args) {
        ObservationGuard guard = new ObservationGuard();
        UUID world=UUID.randomUUID(), other=UUID.randomUUID(), p=UUID.randomUUID();
        long t=1_000_000;
        guard.observe(p,world,0,0,0,0,10,t);
        check(guard.protectedChunk(world,0,0,t));
        check(guard.protectedPoint(world,210,0,t));
        check(!guard.protectedPoint(world,260,0,t));
        check(!guard.protectedChunk(other,0,0,t));
        // A single 3.0-block/tick flight vector projects 120 blocks forward.
        guard.observe(p,world,0,0,3.0,0.0,10,t+1001);
        check(guard.protectedPoint(world,305,0,t+1001));
        check(!guard.protectedPoint(world,410,0,t+1001));
        // Instant teleport must retain the old view area even inside the same 1-second window.
        guard.observeTeleport(p,world,900,0,10,t+1100);
        check(guard.protectedPoint(world,0,0,t+1100));
        check(guard.protectedPoint(world,900,0,t+1100));
        // 30 players in three different locations share one visibility envelope.
        UUID[] users=new UUID[30];
        for(int i=0;i<users.length;i++) {
            users[i]=UUID.randomUUID();
            guard.observe(users[i],world,1000+i*600,100,0,0,10,t+2000);
            check(guard.protectedPoint(world,1000+i*600,100,t+2000));
        }
        check(guard.trackedPlayers()==31);
        guard.observe(users[0],world,5000,100,0,0,10,t+3000); // teleport
        check(guard.protectedPoint(world,5000,100,t+3000));
        check(guard.protectedPoint(world,1000,100,t+3000)); // old area not immediately repopulated
        check(!guard.protectedPoint(world,1000,100,t+20_000)); // expires
        guard.forget(p);
        check(guard.trackedPlayers()==30);
        guard.prune(t+20_000);
        check(guard.trackedPlayers()==0);
        // Velocity spike cannot protect arbitrary thousands of blocks.
        guard.observe(p,world,0,0,1000,0,10,t+30_000);
        check(guard.protectedPoint(world,510,0,t+30_000));
        check(!guard.protectedPoint(world,600,0,t+30_000));
        System.out.println("ObservationGuardHarness: "+checks+" checks passed");
    }
}
