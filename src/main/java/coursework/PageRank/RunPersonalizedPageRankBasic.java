/**
 * This implementation refers to Bespin's implementations of "big data" algorithms
 */

package coursework.PageRank;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;
import tl.lin.data.array.ArrayListOfIntsWritable;


import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * <p>
 * Main driver program for running the basic (non-Schimmy) implementation of
 * PageRank.
 * </p>
 * @author Xintong Li
 */
public class RunPersonalizedPageRankBasic extends Configured implements Tool {
    private static final Logger LOG = Logger.getLogger(RunPersonalizedPageRankBasic.class);

    private static enum PageRank {
        nodes, edges, massMessages, massMessagesSaved, massMessagesReceived, missingStructure
    };
    private static final String SOURCE_NODES_FIELD = "source.nodes";

    /**
     * Mapper, no in-mapper combining.
     *  Distribute the PageRank mass of each node to its outgoing links.
     *  For a dangling node, distribute its mass to the source nodes
     *  But dangling nodes are handled in BuildPersonalizedPageRankRecords.java
     */
    private static class MapClass extends
            Mapper<IntWritable, PageRankNode, IntWritable, PageRankNode> {

        // The neighbor to which we're sending messages.
        private static final IntWritable neighbor = new IntWritable();

        // Contents of the messages: partial PageRank mass.
        private static final PageRankNode intermediateMass = new PageRankNode();

        // For passing along node structure.
        private static final PageRankNode intermediateStructure = new PageRankNode();

        @Override
        public void map(IntWritable nid, PageRankNode node, Context context)
                throws IOException, InterruptedException {
            // Pass along node structure.
            intermediateStructure.setNodeId(node.getNodeId());
            intermediateStructure.setType(PageRankNode.Type.Structure);
            intermediateStructure.setAdjacencyList(node.getAdjacencyList());

            context.write(nid, intermediateStructure);

            int massMessages = 0;

            // Distribute PageRank mass to neighbors (along outgoing edges).
            if (node.getAdjacencyList().size() > 0) {
                // Each neighbor gets an equal share of PageRank mass.
                ArrayListOfIntsWritable list = node.getAdjacencyList();
                float mass = node.getPageRank() - (float) StrictMath.log(list.size());

                context.getCounter(PageRank.edges).increment(list.size());

                // Iterate over neighbors.
                for (int i = 0; i < list.size(); i++) {
                    neighbor.set(list.get(i));
                    intermediateMass.setNodeId(list.get(i));
                    intermediateMass.setType(PageRankNode.Type.Mass);
                    intermediateMass.setPageRank(mass);

                    // Emit messages with PageRank mass to neighbors.
                    context.write(neighbor, intermediateMass);
//                    System.out.println("Mapper emit (" + neighbor.get() + "," + Math.exp(mass) + ")");
                    massMessages++;
                }
            } else {
                // This shouldn't happen!
                throw new RuntimeException("Encountered node without neighbors: " + nid.get());
            }

            // Bookkeeping.
            context.getCounter(PageRank.nodes).increment(1);
            context.getCounter(PageRank.massMessages).increment(massMessages);
        }
    }

    // Combiner: sums partial PageRank contributions and passes node structure along.
    private static class CombineClass extends
            Reducer<IntWritable, PageRankNode, IntWritable, PageRankNode> {
        private static final PageRankNode intermediateMass = new PageRankNode();

        @Override
        public void reduce(IntWritable nid, Iterable<PageRankNode> values, Context context)
                throws IOException, InterruptedException {
            int massMessages = 0;

            // Remember, PageRank mass is stored as a log prob.
            float mass = Float.NEGATIVE_INFINITY;
            for (PageRankNode n : values) {
                if (n.getType() == PageRankNode.Type.Structure) {
                    // Simply pass along node structure.
                    context.write(nid, n);
                } else {
                    // Accumulate PageRank mass contributions.
                    mass = sumLogProbs(mass, n.getPageRank());
                    massMessages++;
                }
            }

            // Emit aggregated results.
            if (massMessages > 0) {
                intermediateMass.setNodeId(nid.get());
                intermediateMass.setType(PageRankNode.Type.Mass);
                intermediateMass.setPageRank(mass);

                context.write(nid, intermediateMass);
            }
        }
    }

    /**
     * Reduce: sums incoming PageRank contributions, rewrite graph structure.
     *  Also handle the random jumps here:
     *      the random jump is always back to one of the source nodes randomly
     */
    private static class ReduceClass extends
            Reducer<IntWritable, PageRankNode, IntWritable, PageRankNode> {
        // For keeping track of PageRank mass encountered, so we can compute missing PageRank mass lost
        // through dangling nodes.
        private float totalMass = Float.NEGATIVE_INFINITY;
        // Store the parsed source node IDs.
        private static Set<Integer> sourceNodes;

        @Override
        public void setup(Reducer<IntWritable, PageRankNode, IntWritable, PageRankNode>.Context context) {
            if (sourceNodes == null) {
                sourceNodes = new HashSet<>();
                String[] sourceNodeIds = context.getConfiguration().get(SOURCE_NODES_FIELD, "").split(",");
                for (String nodeId : sourceNodeIds) {
                    sourceNodes.add(Integer.parseInt(nodeId.trim()));
                }
            }
            System.out.println("Number of sources received by Reducer is " + sourceNodes.size());
        }

        @Override
        public void reduce(IntWritable nid, Iterable<PageRankNode> iterable, Context context)
                throws IOException, InterruptedException {
            Iterator<PageRankNode> values = iterable.iterator();

            // Create the node structure that we're going to assemble back together from shuffled pieces.
            PageRankNode node = new PageRankNode();

            node.setType(PageRankNode.Type.Complete);
            node.setNodeId(nid.get());

            int massMessagesReceived = 0;
            int structureReceived = 0;

            float mass = Float.NEGATIVE_INFINITY;
            while (values.hasNext()) {
                PageRankNode n = values.next();

                if (n.getType().equals(PageRankNode.Type.Structure)) {
                    // This is the structure; update accordingly.
                    ArrayListOfIntsWritable list = n.getAdjacencyList();
                    structureReceived++;

                    node.setAdjacencyList(list);
                } else {
                    // This is a message that contains PageRank mass; accumulate.
                    mass = sumLogProbs(mass, n.getPageRank());
                    massMessagesReceived++;
                }
            }

            // only jump to one of the source nodes
            float jump = Float.NEGATIVE_INFINITY;
            if (sourceNodes.contains(nid.get())){
                jump = (float) (Math.log(ALPHA) - Math.log(sourceNodes.size()));
            }
            float link = (float) Math.log(1.0f - ALPHA) + mass;
//            System.out.println("Node " + nid.get() + " jump=" + Math.exp(jump));
//            System.out.println("Node " + nid.get() + " link=" + Math.exp(link));

            mass = sumLogProbs(jump, link);

            // Update the final accumulated PageRank mass.
            node.setPageRank(mass);
            context.getCounter(PageRank.massMessagesReceived).increment(massMessagesReceived);

            // Error checking.
            if (structureReceived == 1) {
                // Everything checks out, emit final node structure with updated PageRank value.
                context.write(nid, node);
//                System.out.println("Reducer emit (" + nid.get() + "," + Math.exp(mass) + ")");

                // Keep track of total PageRank mass.
                totalMass = sumLogProbs(totalMass, mass);
            } else if (structureReceived == 0) {
                // We get into this situation if there exists an edge pointing to a node which has no
                // corresponding node structure (i.e., PageRank mass was passed to a non-existent node)...
                // log and count but move on.
                context.getCounter(PageRank.missingStructure).increment(1);
                LOG.warn("No structure received for nodeid: " + nid.get() + " mass: "
                        + massMessagesReceived);
                // It's important to note that we don't add the PageRank mass to total... if PageRank mass
                // was sent to a non-existent node, it should simply vanish.
            } else {
                throw new RuntimeException("Multiple structure received for nodeid: " + nid.get()
                        + " mass: " + massMessagesReceived + " struct: " + structureReceived);
            }
        }
    }

    // Random jump factor.
    private static float ALPHA = 0.15f;
    private static NumberFormat formatter = new DecimalFormat("0000");

    /**
     * Dispatches command-line arguments to the tool via the {@code ToolRunner}.
     *
     * @param args command-line arguments
     * @throws Exception if tool encounters an exception
     */
    public static void main(String[] args) throws Exception {
        ToolRunner.run(new RunPersonalizedPageRankBasic(), args);
    }

    public RunPersonalizedPageRankBasic() {}

    private static final String BASE = "base";
    private static final String NUM_NODES = "numNodes";
    private static final String START = "start";
    private static final String END = "end";
    private static final String SOURCES = "sources";

    /**
     * Runs this tool.
     */
    @SuppressWarnings({ "static-access" })
    public int run(String[] args) throws Exception {
        Options options = new Options();
        options.addOption(OptionBuilder.withArgName("path").hasArg()
                .withDescription("base path").create(BASE));
        options.addOption(OptionBuilder.withArgName("num").hasArg()
                .withDescription("start iteration").create(START));
        options.addOption(OptionBuilder.withArgName("num").hasArg()
                .withDescription("end iteration").create(END));
        options.addOption(OptionBuilder.withArgName("num").hasArg()
                .withDescription("number of nodes").create(NUM_NODES));
        options.addOption(OptionBuilder.withArgName("node ids").hasArg()
                .withDescription("source nodes").create(SOURCES));

        CommandLine cmdline;
        CommandLineParser parser = new GnuParser();

        try {
            cmdline = parser.parse(options, args);
        } catch (ParseException exp) {
            System.err.println("Error parsing command line: " + exp.getMessage());
            return -1;
        }

        if (!cmdline.hasOption(BASE) || !cmdline.hasOption(START) || !cmdline.hasOption(END)
                || !cmdline.hasOption(NUM_NODES) || !cmdline.hasOption(SOURCES) ) {
            System.out.println("args: " + Arrays.toString(args));
            HelpFormatter formatter = new HelpFormatter();
            formatter.setWidth(120);
            formatter.printHelp(this.getClass().getName(), options);
            ToolRunner.printGenericCommandUsage(System.out);
            return -1;
        }

        String basePath = cmdline.getOptionValue(BASE);
        int n = Integer.parseInt(cmdline.getOptionValue(NUM_NODES));
        int s = Integer.parseInt(cmdline.getOptionValue(START));
        int e = Integer.parseInt(cmdline.getOptionValue(END));
        String sourceNodes = cmdline.getOptionValue(SOURCES);

        LOG.info("Tool name: RunPageRank");
        LOG.info(" - base path: " + basePath);
        LOG.info(" - num nodes: " + n);
        LOG.info(" - start iteration: " + s);
        LOG.info(" - end iteration: " + e);
        LOG.info(" - sources: " + sourceNodes);

        // Iterate PageRank.
        for (int i = s; i < e; i++) {
            iteratePageRank(i, i + 1, basePath, n, sourceNodes);
        }

        return 0;
    }

    // Run each iteration.
    private void iteratePageRank(int i, int j, String basePath, int numNodes,
                                 String sources) throws Exception {
        // Mapper: PageRank mass along outgoing edges.
        // Reducer: distribute missing mass, take care of random jump factor.
        phase1(i, j, basePath, numNodes, sources);
    }

    private void phase1(int i, int j, String basePath, int numNodes,
                        String sources) throws Exception {
        Job job = Job.getInstance(getConf());
        job.setJobName("PageRank:Basic:iteration" + j + ":Phase1");
        job.setJarByClass(RunPersonalizedPageRankBasic.class);

        String in = basePath + "/iter" + formatter.format(i);
        String out = basePath + "/iter" + formatter.format(j);

        // We need to actually count the number of part files to get the number of partitions (because
        // the directory might contain _log).
        int numPartitions = 0;
        for (FileStatus s : FileSystem.get(getConf()).listStatus(new Path(in))) {
            if (s.getPath().getName().contains("part-"))
                numPartitions++;
        }

        LOG.info("PageRank: iteration " + j + ": Phase1");
        LOG.info(" - input: " + in);
        LOG.info(" - output: " + out);
        LOG.info(" - nodeCnt: " + numNodes);
        LOG.info("computed number of partitions: " + numPartitions);
        LOG.info(" - sources: " + sources);

        int numReduceTasks = numPartitions;

        job.getConfiguration().setInt("NodeCount", numNodes);
        job.getConfiguration().setBoolean("mapred.map.tasks.speculative.execution", false);
        job.getConfiguration().setBoolean("mapred.reduce.tasks.speculative.execution", false);
        //job.getConfiguration().set("mapred.child.java.opts", "-Xmx2048m");
        job.getConfiguration().set(SOURCE_NODES_FIELD, sources);

        job.setNumReduceTasks(numReduceTasks);

        FileInputFormat.setInputPaths(job, new Path(in));
        FileOutputFormat.setOutputPath(job, new Path(out));

        job.setInputFormatClass(NonSplitableSequenceFileInputFormat.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);

        job.setMapOutputKeyClass(IntWritable.class);
        job.setMapOutputValueClass(PageRankNode.class);

        job.setOutputKeyClass(IntWritable.class);
        job.setOutputValueClass(PageRankNode.class);

        job.setMapperClass(MapClass.class);

        job.setCombinerClass(CombineClass.class);

        job.setReducerClass(ReduceClass.class);

        FileSystem.get(getConf()).delete(new Path(out), true);

        long startTime = System.currentTimeMillis();
        job.waitForCompletion(true);
        System.out.println("Job Finished in " + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

    }


    // Adds two log probs.
    private static float sumLogProbs(float a, float b) {
        if (a == Float.NEGATIVE_INFINITY)
            return b;

        if (b == Float.NEGATIVE_INFINITY)
            return a;

        if (a < b) {
            return (float) (b + StrictMath.log1p(StrictMath.exp(a - b)));
        }

        return (float) (a + StrictMath.log1p(StrictMath.exp(b - a)));
    }
}
