package dev.deadstreets.paper;

import dev.deadstreets.core.MetropolisFlag;
import org.bukkit.Chunk;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Write-ahead population-slice journal independent of Minecraft chunk/entity save order.
 * The cursor must reach disk BEFORE a zombie is created. A crash can leave an unused
 * ordinal consumed, never replay an ordinal whose walker was already materialized.
 * Records are small (one cursor movement per actual attempted spawn) and globally
 * limited by WalkerBudget. The database does not include Metropolis resources.
 */
final class PopulationJournal {
    private final DeadStreetsPlugin plugin;
    private final Map<String,Integer> positions = new HashMap<>();
    private boolean healthy=true;
    PopulationJournal(DeadStreetsPlugin plugin) {this.plugin=plugin;}
    private Path file() {return plugin.getDataFolder().toPath().resolve("population-writeahead-v1.log");}
    static String key(MetropolisFlag flag, Chunk chunk) {
        return flag.worldId()+":"+flag.worldSeed()+":"+flag.buildId()+":"+chunk.getX()+":"+chunk.getZ();
    }
    void load() {
        if(!Files.isRegularFile(file()))return;
        try(BufferedReader in=Files.newBufferedReader(file(),StandardCharsets.UTF_8)) {
            String line;int count=0;
            while((line=in.readLine())!=null) {
                if(++count>1_000_000)throw new IOException("Write-ahead population log exceeds row safety cap");
                int sep=line.lastIndexOf('\t');
                if(sep<1)throw new IOException("Malformed population journal row");
                int cursor=Integer.parseInt(line.substring(sep+1));
                if(cursor<0 || cursor>256)throw new IOException("Invalid population cursor");
                positions.merge(line.substring(0,sep),cursor,Math::max);
            }
        }catch(Exception ex){healthy=false;plugin.getLogger().severe("Population journal invalid; activation FAILS CLOSED: "+ex.getMessage());}
    }
    int cursor(String key) {return positions.getOrDefault(key,0);}
    boolean write(String key,int cursor) {
        if(!healthy)return false;
        if(cursor<=cursor(key))return true;
        try {
            Files.createDirectories(file().getParent());
            byte[] data=(key+"\t"+cursor+"\n").getBytes(StandardCharsets.UTF_8);
            try(FileChannel ch=FileChannel.open(file(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
                ByteBuffer b=ByteBuffer.wrap(data);
                while(b.hasRemaining())ch.write(b);
                ch.force(true);
            }
            positions.put(key,cursor);
            return true;
        }catch(IOException ex){healthy=false;plugin.getLogger().severe("Cannot persist population cursor; activation frozen: "+ex.getMessage());return false;}
    }
    int distinctSlices() {return positions.size();}
}
