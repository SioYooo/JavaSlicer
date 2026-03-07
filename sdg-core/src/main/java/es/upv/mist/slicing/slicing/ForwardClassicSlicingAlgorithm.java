package es.upv.mist.slicing.slicing;

import es.upv.mist.slicing.arcs.Arc;
import es.upv.mist.slicing.graphs.Graph;
import es.upv.mist.slicing.nodes.GraphNode;
import es.upv.mist.slicing.utils.Utils;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Forward slicing algorithm: the dual of {@link ClassicSlicingAlgorithm}.
 * <p>
 * Traverses all arcs <em>forwards</em> (outgoing) instead of backwards (incoming),
 * using the same two-pass interprocedural strategy (Horwitz, Reps &amp; Binkley).
 * The ignore conditions remain identical:
 * <ul>
 *   <li>Pass 1: ignore interprocedural output arcs (no ascending from callee to caller)</li>
 *   <li>Pass 2: ignore interprocedural input arcs (no descending from caller to callee)</li>
 * </ul>
 */
public class ForwardClassicSlicingAlgorithm extends ClassicSlicingAlgorithm {

    public ForwardClassicSlicingAlgorithm(Graph graph) {
        super(graph);
    }

    /**
     * A single forward pass: edges are traversed in the <em>outgoing</em> direction
     * until no new node can be added.
     */
    @Override
    protected void pass(Slice slice, Predicate<Arc> ignoreCondition) {
        LinkedHashSet<GraphNode<?>> toVisit = new LinkedHashSet<>(slice.getGraphNodes());
        Set<GraphNode<?>> visited = new HashSet<>();

        while (!toVisit.isEmpty()) {
            GraphNode<?> node = Utils.setPop(toVisit);
            if (visited.contains(node))
                continue;
            visited.add(node);
            // Forward: follow outgoing edges (src -> dst)
            for (Arc arc : graph.outgoingEdgesOf(node)) {
                if (ignoreCondition.test(arc))
                    continue;
                GraphNode<?> target = graph.getEdgeTarget(arc);
                if (!visited.contains(target))
                    toVisit.add(target);
            }
        }

        visited.forEach(slice::add);
    }
}
