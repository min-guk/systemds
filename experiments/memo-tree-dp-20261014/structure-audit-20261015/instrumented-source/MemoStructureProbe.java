package org.apache.sysds.hops.fedplanner.memo;

import java.util.*;
import org.apache.sysds.hops.Hop;

/** Diagnostic-only graph census; never evaluates or changes rules, factors, or domains. */
public final class MemoStructureProbe {
    private MemoStructureProbe() { }

    public static void factors(String stage, int[] cardinalities,
        List<int[]> checks, List<int[]> costs) {
        List<int[]> scopes = new ArrayList<>(checks);
        scopes.addAll(costs);
        BitSet active = new BitSet();
        for(int v=0; v<cardinalities.length; v++) if(cardinalities[v]>1) active.set(v);
        BitSet[] graph = new BitSet[cardinalities.length];
        for(int v=0; v<graph.length; v++) graph[v]=new BitSet();
        TreeMap<Integer,Integer> arities = new TreeMap<>();
        for(int[] scope: scopes) {
            BitSet live = new BitSet();
            for(int v: scope) if(active.get(v)) live.set(v);
            arities.merge(live.cardinality(),1,Integer::sum);
            for(int v=live.nextSetBit(0); v>=0; v=live.nextSetBit(v+1)) {
                graph[v].or(live); graph[v].clear(v);
            }
        }
        census(stage,graph,active,"\"modelSlots\":"+cardinalities.length
            +",\"checkFactors\":"+checks.size()+",\"costFactors\":"+costs.size()
            +",\"effectiveScopeHistogram\":"+histogram(arities));
    }

    public static void hops(List<MemoProgramFacts.Occurrence> occurrences) {
        Map<Long,Hop> nodes = new LinkedHashMap<>();
        for(var occurrence: occurrences) nodes.put(occurrence.hop().getHopID(),occurrence.hop());
        Map<Long,Integer> index = new HashMap<>();
        int position=0;
        for(long id:nodes.keySet()) index.put(id,position++);
        BitSet[] graph=new BitSet[nodes.size()];
        BitSet[] consumers=new BitSet[nodes.size()];
        for(int i=0;i<graph.length;i++) { graph[i]=new BitSet(); consumers[i]=new BitSet(); }
        int inputOccurrences=0, outsideInputs=0;
        for(Hop consumer:nodes.values()) for(Hop input:consumer.getInput()) {
            inputOccurrences++;
            Integer u=index.get(input.getHopID());
            if(u==null) { outsideInputs++; continue; }
            int v=index.get(consumer.getHopID());
            if(u!=v) { graph[u].set(v); graph[v].set(u); consumers[u].set(v); }
        }
        int multiParent=0;
        for(BitSet parents:consumers) if(parents.cardinality()>1) multiParent++;
        BitSet active=new BitSet(); active.set(0,nodes.size());
        census("raw-hop-input-graph",graph,active,"\"occurrences\":"+occurrences.size()
            +",\"multiConsumerHops\":"+multiParent+",\"inputOccurrences\":"+inputOccurrences
            +",\"inputsOutsideOccurrences\":"+outsideInputs);
    }

    private static void census(String stage, BitSet[] graph, BitSet active, String extra) {
        int edges=0;
        TreeMap<Integer,Integer> degrees=new TreeMap<>();
        for(int v=active.nextSetBit(0); v>=0; v=active.nextSetBit(v+1)) {
            edges+=graph[v].cardinality();
            degrees.merge(graph[v].cardinality(),1,Integer::sum);
        }
        edges/=2;
        BitSet seen=new BitSet();
        List<Integer> components=new ArrayList<>(), treeComponents=new ArrayList<>();
        for(int v=active.nextSetBit(0); v>=0; v=active.nextSetBit(v+1)) {
            if(seen.get(v)) continue;
            ArrayDeque<Integer> pending=new ArrayDeque<>(); pending.add(v); seen.set(v);
            int count=0, doubledEdges=0;
            while(!pending.isEmpty()) {
                int u=pending.remove(); count++; doubledEdges+=graph[u].cardinality();
                for(int w=graph[u].nextSetBit(0); w>=0; w=graph[u].nextSetBit(w+1))
                    if(!seen.get(w)) { seen.set(w); pending.add(w); }
            }
            components.add(count);
            if(doubledEdges/2==count-1) treeComponents.add(count);
        }
        components.sort(Comparator.reverseOrder()); treeComponents.sort(Comparator.reverseOrder());
        int[] degree=new int[graph.length];
        ArrayDeque<Integer> leaves=new ArrayDeque<>();
        BitSet core=(BitSet)active.clone();
        for(int v=active.nextSetBit(0); v>=0; v=active.nextSetBit(v+1)) {
            degree[v]=graph[v].cardinality(); if(degree[v]<=1) leaves.add(v);
        }
        while(!leaves.isEmpty()) {
            int v=leaves.remove(); if(!core.get(v)) continue; core.clear(v);
            for(int w=graph[v].nextSetBit(0); w>=0; w=graph[v].nextSetBit(w+1))
                if(core.get(w) && --degree[w]==1) leaves.add(w);
        }
        System.err.println("[MemoStructure] {\"stage\":\""+stage+"\","
            +extra+",\"vertices\":"+active.cardinality()+",\"edges\":"+edges
            +",\"componentSizes\":"+components+",\"treeComponentSizes\":"+treeComponents
            +",\"degreeHistogram\":"+histogram(degrees)
            +",\"leafPeelVertices\":"+(active.cardinality()-core.cardinality())
            +",\"twoCoreVertices\":"+core.cardinality()
            +",\"cycleRank\":"+(edges-active.cardinality()+components.size())+"}");
    }

    private static String histogram(Map<Integer,Integer> values) {
        StringJoiner out=new StringJoiner(",","{","}");
        values.forEach((key,value)->out.add("\""+key+"\":"+value));
        return out.toString();
    }
}
