package org.locationtech.geogig.api.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.locationtech.geogig.api.plumbing.diff.RevObjectTestSupport.featureNode;
import static org.locationtech.geogig.api.plumbing.diff.RevObjectTestSupport.featureNodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.locationtech.geogig.api.Bucket;
import org.locationtech.geogig.api.LegacyTreeBuilder;
import org.locationtech.geogig.api.Node;
import org.locationtech.geogig.api.NodeRef;
import org.locationtech.geogig.api.ObjectId;
import org.locationtech.geogig.api.RevObject.TYPE;
import org.locationtech.geogig.api.RevTree;
import org.locationtech.geogig.api.RevTreeImpl;
import org.locationtech.geogig.api.plumbing.diff.DepthTreeIterator;
import org.locationtech.geogig.api.plumbing.diff.DepthTreeIterator.Strategy;
import org.locationtech.geogig.repository.SpatialOps;
import org.locationtech.geogig.storage.NodePathStorageOrder;
import org.locationtech.geogig.storage.NodeStorageOrder;
import org.locationtech.geogig.storage.ObjectStore;
import org.locationtech.geogig.storage.memory.HeapObjectStore;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

public abstract class CanonicalClusteringStrategyTest {

    private DAGStorageProviderFactory storageProvider;

    private ObjectStore store;

    private ClusteringStrategyFactory canonical;

    private ClusteringStrategy strategy;

    @Before
    public void before() {
        canonical = ClusteringStrategyFactory.canonical();
        store = new HeapObjectStore();
        store.open();
        storageProvider = createStorageProvider(store);
    }

    protected abstract DAGStorageProviderFactory createStorageProvider(ObjectStore source);

    @After
    public void after() {
        if (strategy != null) {
            strategy.dispose();
        }
        store.close();
    }

    @Test
    public void buildSimpleDAGFromScratch() {
        strategy = canonical.create(RevTree.EMPTY, storageProvider);
        for (int i = 0; i < strategy.normalizedSizeLimit(0); i++) {
            Node node = featureNode("f", i);
            strategy.put(node);
        }
        DAG root = strategy.getRoot();
        assertNull(root.unpromotable());
        assertNull(root.buckets());
        assertNotNull(root.children());
        assertEquals(strategy.normalizedSizeLimit(0), root.children().size());
        assertEquals(0, strategy.depth());
    }

    @Test
    public void buildSplittedDAGFromScratch() {
        strategy = canonical.create(RevTree.EMPTY, storageProvider);

        final int numNodes = 2 * strategy.normalizedSizeLimit(0);

        for (int i = 0; i < numNodes; i++) {
            Node node = featureNode("f", i, true);
            strategy.put(node);
        }
        DAG root = strategy.getRoot();
        assertNull(root.unpromotable());
        assertNull(root.children());
        assertNotNull(root.buckets());
        assertEquals(1, strategy.depth());

        List<NodeId> flattenedNodes = flatten(root);
        assertEquals(numNodes, flattenedNodes.size());

    }

    @Test
    public void promoteLeafNodes() {
        final RevTree original = manuallyCreateLeafTree(NodePathStorageOrder.normalizedSizeLimit(0));
        store.put(original);

        strategy = canonical.create(original, storageProvider);

        final int numNodes = 2 * strategy.normalizedSizeLimit(0);

        Stopwatch sw = Stopwatch.createStarted();
        for (int i = 0; i < numNodes; i++) {
            Node node = featureNode("f", i, true);
            strategy.put(node);
        }
        System.err.printf("Added %,d nodes in %s\n", numNodes, sw.stop());

        DAG root = strategy.getRoot();
        assertNull(root.unpromotable());
        assertNull(root.children());
        assertNotNull(root.buckets());
        assertEquals(1, strategy.depth());

        List<NodeId> flattenedNodes = flatten(root);
        assertEquals(numNodes, flattenedNodes.size());

    }

    @Test
    public void promoteBucketNodes() {
        final RevTree original = manuallyCreateBucketsTree();
        store.put(original);

        strategy = canonical.create(original, storageProvider);

        final int numNodes = 10_000;

        Stopwatch sw = Stopwatch.createStarted();
        for (int i = 0; i < numNodes; i++) {
            Node node = featureNode("f", i, false);
            strategy.put(node);
        }
        System.err.printf("Added %,d nodes in %s\n", numNodes, sw.stop());

        DAG root = strategy.getRoot();
        assertNull(root.unpromotable());
        assertNull(root.children());
        assertNotNull(root.buckets());
        // assertEquals(1, strategy.depth());

        List<NodeId> flattenedNodes = flatten(root);
        assertEquals(numNodes, flattenedNodes.size());

    }

    @Test
    public void promoteBucketNodes2() {

        final List<Node> nodes = featureNodes(0, 10_000, false);
        final List<Node> origNodes = nodes.subList(0, 5_000);
        final List<Node> addedNodes = nodes.subList(5_000, nodes.size());
        final RevTree original;
        {
            LegacyTreeBuilder legacyBuilder = new LegacyTreeBuilder(store);
            for (Node n : origNodes) {
                legacyBuilder.put(n);
            }
            original = legacyBuilder.build();
        }
        // original = manuallyCreateBucketsTree();
        store.put(original);

        strategy = canonical.create(original, storageProvider);

        Stopwatch sw = Stopwatch.createStarted();
        for (Node node : addedNodes) {
            strategy.put(node);
        }
        System.err.printf("Added %,d nodes in %s\n", addedNodes.size(), sw.stop());

        DAG root = strategy.getRoot();
        assertNull(root.unpromotable());
        assertNull(root.children());
        assertNotNull(root.buckets());
        // assertEquals(1, strategy.depth());

        List<NodeId> flattenedNodes = flatten(root);
        assertEquals(nodes.size(), flattenedNodes.size());

    }

    @Test
    public void promoteBucketNodesWithOverlap() {

        final List<Node> nodes = featureNodes(0, 10_000, false);
        final List<Node> origNodes = nodes.subList(0, 7_000);
        final List<Node> addedNodes = nodes.subList(3_000, nodes.size());
        final RevTree original;
        {
            LegacyTreeBuilder legacyBuilder = new LegacyTreeBuilder(store);
            for (Node n : origNodes) {
                legacyBuilder.put(n);
            }
            original = legacyBuilder.build();
        }
        // original = manuallyCreateBucketsTree();
        store.put(original);

        strategy = canonical.create(original, storageProvider);

        Stopwatch sw = Stopwatch.createStarted();
        for (Node node : addedNodes) {
            strategy.put(node);
        }
        System.err.printf("Added %,d nodes in %s\n", addedNodes.size(), sw.stop());

        DAG root = strategy.getRoot();
        assertNull(root.unpromotable());
        assertNull(root.children());
        assertNotNull(root.buckets());
        // assertEquals(1, strategy.depth());

        List<NodeId> flattenedNodes = flatten(root);
        assertEquals(nodes.size(), flattenedNodes.size());

    }

    @Test
    public void randomEdits() throws Exception {
        final int numEntries = 20 * NodePathStorageOrder.normalizedSizeLimit(0) + 1500;

        strategy = canonical.create(RevTree.EMPTY, storageProvider);

        List<Node> nodes = featureNodes(0, numEntries, false);
        for (Node n : nodes) {
            strategy.put(n);
        }

        Set<Node> initial = Sets.newTreeSet(Lists.transform(flatten(strategy.getRoot()),
                nid -> strategy.getNode(nid)));
        assertEquals(nodes.size(), initial.size());

        final Map<Integer, Node> randomEdits = Maps.newHashMap();
        {
            Random randGen = new Random();
            for (int i = 0; i < numEntries / 2; i++) {
                int random;
                while (randomEdits.containsKey(random = randGen.nextInt(numEntries))) {
                    ; // $codepro.audit.disable extraSemicolon
                }
                Node n = featureNode("f", random, true);
                randomEdits.put(random, n);
            }

            for (Node ref : randomEdits.values()) {
                NodeId nodeId = strategy.computeId(ref);
                Node currNode = strategy.getNode(nodeId);
                strategy.put(ref);
                Node newNode = strategy.getNode(nodeId);
                assertFalse(currNode.equals(newNode));
            }
        }

        Set<Node> result = Sets.newTreeSet(Lists.transform(flatten(strategy.getRoot()),
                nid -> strategy.getNode(nid)));
        assertEquals(nodes.size(), result.size());

        Set<Node> difference = Sets.difference(Sets.newHashSet(result), Sets.newHashSet(initial));
        assertEquals(randomEdits.size(), difference.size());

        assertEquals(new HashSet<>(randomEdits.values()), difference);
    }

    @Test
    public void bucketDAGShrinksOnRemoveBellowThreshold() {

        final List<Node> nodes = featureNodes(0, 513, false);
        final List<Node> removeNodes = nodes.subList(100, 500);
        final RevTree original;
        {
            LegacyTreeBuilder legacyBuilder = new LegacyTreeBuilder(store);
            for (Node n : nodes) {
                legacyBuilder.put(n);
            }
            original = legacyBuilder.build();
        }
        // original = manuallyCreateBucketsTree();
        store.put(original);

        strategy = canonical.create(original, storageProvider);

        Stopwatch sw = Stopwatch.createStarted();
        for (Node node : removeNodes) {
            strategy.remove(node.getName());
        }
        System.err.printf("Removed %,d nodes in %s\n", removeNodes.size(), sw.stop());

        DAG root = strategy.getRoot();
        assertNull(root.unpromotable());
        assertNotNull(root.children());
        assertNull(root.buckets());
        // assertEquals(1, strategy.depth());

        List<NodeId> flattenedNodes = flatten(root);
        assertEquals(nodes.size() - removeNodes.size(), flattenedNodes.size());

        assertNull(root.buckets());
        assertNotNull(root.children());
        assertEquals(nodes.size() - removeNodes.size(), root.children().size());
    }

    @Test
    public void bigBucketDAGShrinksOnRemoveBellowThreshold() {

        final List<Node> nodes = featureNodes(0, 32768, false);
        final List<Node> removeNodes = nodes.subList(100, 32700);
        final RevTree original;
        {
            LegacyTreeBuilder legacyBuilder = new LegacyTreeBuilder(store);
            for (Node n : nodes) {
                legacyBuilder.put(n);
            }
            original = legacyBuilder.build();
        }
        // original = manuallyCreateBucketsTree();
        store.put(original);

        strategy = canonical.create(original, storageProvider);

        Stopwatch sw = Stopwatch.createStarted();
        for (Node node : removeNodes) {
            strategy.remove(node.getName());
        }
        System.err.printf("Removed %,d nodes in %s\n", removeNodes.size(), sw.stop());

        DAG root = strategy.getRoot();
        assertEquals(nodes.size() - removeNodes.size(), root.childCount);
        assertNull(root.unpromotable());
        assertNotNull(root.children());
        assertNull(root.buckets());
        // assertEquals(1, strategy.depth());

        List<NodeId> flattenedNodes = flatten(root);
        assertEquals(nodes.size() - removeNodes.size(), flattenedNodes.size());

        assertNull(root.buckets());
        assertNotNull(root.children());
        assertEquals(nodes.size() - removeNodes.size(), root.children().size());
    }

    @Test
    public void nodeReplacedOnEdits() {
        strategy = canonical.create(RevTree.EMPTY, storageProvider);

        final int numNodes = 2 * strategy.normalizedSizeLimit(0);

        final Set<Node> original;
        final Set<Node> edited;
        {
            original = new HashSet<>();
            edited = new HashSet<>();
            for (int i = 0; i < numNodes; i++) {
                Node orig = featureNode("f", i, false);
                Node edit = featureNode("f", i, true);

                original.add(orig);
                edited.add(edit);
            }
        }

        assertFalse(original.equals(edited));

        for (Node n : original) {
            strategy.put(n);
        }

        Set<Node> originalResult = new HashSet<>();
        Set<Node> edittedResult = new HashSet<>();

        DAG root = strategy.getRoot();
        originalResult.addAll(toNode(flatten(root)));

        assertEquals(original, originalResult);

        for (Node n : edited) {
            strategy.put(n);
        }

        root = strategy.getRoot();
        edittedResult.addAll(toNode(flatten(root)));

        assertEquals(edited, edittedResult);
    }

    @Test
    public void nodeReplacedOnEditsWithBaseRevTree() {
        final RevTree origTree = manuallyCreateBucketsTree();
        store.put(origTree);

        final Set<Node> original = new HashSet<>();
        final Set<Node> edited = new HashSet<>();
        {
            Iterator<NodeRef> it = new DepthTreeIterator("", ObjectId.NULL, origTree, store,
                    Strategy.RECURSIVE_FEATURES_ONLY);
            while (it.hasNext()) {
                original.add(it.next().getNode());
            }
            for (Node n : original) {
                ObjectId oid = ObjectId.forString(n.toString());
                Node edit = Node.create(n.getName(), oid, ObjectId.NULL, TYPE.FEATURE, n.bounds()
                        .orNull());
                edited.add(edit);

            }
            assertFalse(original.equals(edited));
        }

        strategy = canonical.create(origTree, storageProvider);

        for (Node n : edited) {
            strategy.put(n);
        }

        Set<Node> edittedResult = new HashSet<>();

        DAG root = strategy.getRoot();

        edittedResult.addAll(toNode(flatten(root)));

        assertEquals(edited, edittedResult);
    }

    private RevTree manuallyCreateBucketsTree() {
        // final int numNodes = 4096;
        // RevTreeBuilder legacyBuilder = new RevTreeBuilder(store);
        //
        // for (int i = 0; i < numNodes; i++) {
        // boolean randomIds = false;
        // Node node = featureNode("f", i, randomIds);
        // legacyBuilder.put(node);
        // }
        //
        // RevTree tree = legacyBuilder.build();
        // return tree;

        final int numNodes = 4096;

        Multimap<Integer, Node> nodesByBucketIndex = ArrayListMultimap.create();
        for (int i = 0; i < numNodes; i++) {
            boolean randomIds = false;
            Node node = featureNode("f", i, randomIds);
            Integer bucket = NodeStorageOrder.INSTANCE.bucket(node, 0);
            nodesByBucketIndex.put(bucket, node);
        }

        Map<Integer, Bucket> bucketsByIndex = new TreeMap<>();

        for (Integer bucketIndex : new TreeSet<>(nodesByBucketIndex.keySet())) {
            Collection<Node> nodes = nodesByBucketIndex.get(bucketIndex);
            RevTree leaf = createLeafTree(nodes);
            store.put(leaf);
            Bucket bucket = Bucket.create(leaf.getId(), SpatialOps.boundsOf(leaf));
            bucketsByIndex.put(bucketIndex, bucket);
        }

        long size = numNodes;
        int childTreeCount = 0;
        ImmutableList<Node> trees = null;
        ImmutableList<Node> features = null;
        ImmutableSortedMap<Integer, Bucket> buckets = ImmutableSortedMap.copyOf(bucketsByIndex);
        RevTree tree = RevTreeImpl.create(size, childTreeCount, trees, features, buckets);
        return tree;
    }

    private RevTree manuallyCreateLeafTree(final int nodeCount) {
        Preconditions.checkArgument(nodeCount <= NodePathStorageOrder.normalizedSizeLimit(0));

        ImmutableList.Builder<Node> nodes = ImmutableList.builder();
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(featureNode("f", i));
        }
        return createLeafTree(nodes.build());
    }

    private RevTree createLeafTree(Collection<Node> featureNodes) {
        int childTreeCount = 0;
        ImmutableList<Node> trees = null;
        List<Node> sorted = new ArrayList<Node>(featureNodes);
        Collections.sort(sorted, NodeStorageOrder.INSTANCE);
        ImmutableList<Node> features = ImmutableList.copyOf(sorted);
        ImmutableSortedMap<Integer, Bucket> buckets = null;
        int size = features.size();
        RevTree tree = RevTreeImpl.create(size, childTreeCount, trees, features, buckets);
        return tree;
    }

    private List<Node> toNode(List<NodeId> nodeIds) {

        return Lists.transform(nodeIds, (n) -> strategy.getNode(n));
    }

    private List<NodeId> flatten(DAG root) {
        List<NodeId> nodes = new ArrayList<NodeId>();

        SortedSet<NodeId> children = root.children();
        SortedSet<NodeId> unpromotable = root.unpromotable();
        SortedSet<TreeId> buckets = root.buckets();
        if (children != null) {
            nodes.addAll(children);
        }
        if (unpromotable != null) {
            nodes.addAll(unpromotable);
        }
        if (buckets != null) {
            for (TreeId bucketTreeId : buckets) {
                DAG bucketDAG = strategy.getTree(bucketTreeId);
                nodes.addAll(flatten(bucketDAG));
            }
        }
        return nodes;
    }

}
