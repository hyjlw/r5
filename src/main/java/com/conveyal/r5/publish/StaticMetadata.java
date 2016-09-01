package com.conveyal.r5.publish;

import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transitive.TransitiveNetwork;
import com.google.common.io.LittleEndianDataOutputStream;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * Create the per-query files for a static site request.
 */
public class StaticMetadata implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(StaticMetadata.class);

    private StaticSiteRequest request;
    private TransportNetwork network;

    public StaticMetadata (StaticSiteRequest req, TransportNetwork network) {
        this.request = req;
        this.network = network;
    }

    @Override
    public void run () {
        // first generate and write out metadata

        try {
            OutputStream os = StaticDataStore.getOutputStream(request, "query.json", "application/json");
            writeMetadata(os);
            os.close();
        } catch (Exception e) {
            LOG.error("Exception saving static output", e);
            return;
        }

        // write the stop tree
        try {
            OutputStream os = StaticDataStore.getOutputStream(request, "stop_trees.dat", "application/octet-stream");
            writeStopTrees(os);
            os.close();
        } catch (Exception e) {
            LOG.error("Exception writing stop trees", e);
            return;
        }

        // write transitive data
        try {
            OutputStream os = StaticDataStore.getOutputStream(request, "transitive.json", "application/json");
            writeTransitiveData(os);
            os.close();
        } catch (Exception e) {
            LOG.error("Exception writing transitive data", e);
            return;
        }

    }

    public void writeTransitiveData(OutputStream os) throws IOException {
        TransitiveNetwork net = new TransitiveNetwork(network.transitLayer);
        JsonUtilities.objectMapper.writeValue(os, net);
    }

    /** Write metadata for this query */
    public void writeMetadata (OutputStream out) throws IOException {
        Metadata metadata = new Metadata();
        WebMercatorGridPointSet ps = network.getGridPointSet();
        metadata.zoom = ps.zoom;
        metadata.north = ps.north;
        metadata.west = ps.west;
        metadata.width = ps.width;
        metadata.height = ps.height;
        metadata.transportNetwork = request.transportNetworkId;
        metadata.request = request.request;

        JsonUtilities.objectMapper.writeValue(out, metadata);
    }

    /** Write distance tables from stops to PointSet points for this query. */
    public void writeStopTrees (OutputStream out) throws IOException {

        // Build the distance tables.
        LinkedPointSet lps = network.getLinkedGridPointSet();
        if (lps.stopToPointDistanceTables == null) {
            // Null means make all trees, not just those in a certain geographic area.
            lps.makeStopToPointDistanceTables(null);
        }

        // Invert the stop trees (points to stops rather than stops to points).
        TIntList[] distanceTables = new TIntList[lps.pointSet.featureCount()];

        // The first increment will bump stop to 0.
        int stop = -1;
        for (int[] table : lps.stopToPointDistanceTables) {
            // make sure stop always gets incremented
            stop++;
            if (table == null) {
                continue;
            }
            for (int i = 0; i < table.length; i+= 2) {
                // table[i] is the target point index
                if (distanceTables[table[i]] == null) {
                    distanceTables[table[i]] = new TIntArrayList();
                }
                // table[i + 1] is the distance to that point
                distanceTables[table[i]].add(new int[] { stop, (int) (table[i + 1] / 1300 / 60)});
            }
        }

        // Write the tables.
        LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(out);

        int prevStopId = 0;
        int prevTime = 0;

        for (TIntList table : distanceTables) {
            if (table == null) {
                dos.writeInt(0);
            }
            else {
                // divide by two because array is jagged, get number of stops
                dos.writeInt(table.size() / 2);
                for (int i = 0; i < table.size(); i += 2) {
                    int stopId = table.get(i);
                    dos.writeInt(stopId - prevStopId);
                    prevStopId = stopId;

                    int time = table.get(i + 1) / 60;
                    dos.writeInt(time - prevTime);
                    prevTime = time;
                }
            }
        }

        dos.flush();
    }

    public static class Metadata implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Zoom level */
        public int zoom;

        /** westermost pixel */
        public long west;

        /** northernmost pixel */
        public long north;

        /** width, pixels */
        public long width;

        /** height, pixels */
        public long height;

        public String transportNetwork;

        public ProfileRequest request;
    }
}
