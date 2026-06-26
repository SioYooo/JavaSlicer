package es.upv.mist.slicing.cli;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import es.upv.mist.slicing.arcs.pdg.FlowDependencyArc;
import es.upv.mist.slicing.arcs.pdg.StructuralArc;
import es.upv.mist.slicing.graphs.augmented.ASDG;
import es.upv.mist.slicing.graphs.augmented.PSDG;
import es.upv.mist.slicing.graphs.exceptionsensitive.ESSDG;
import es.upv.mist.slicing.graphs.jsysdg.JSysDG;
import es.upv.mist.slicing.graphs.sdg.SDG;
import es.upv.mist.slicing.slicing.FileLineSlicingCriterion;
import es.upv.mist.slicing.slicing.ForwardClassicSlicingAlgorithm;
import es.upv.mist.slicing.slicing.Slice;
import es.upv.mist.slicing.slicing.SlicingCriterion;
import es.upv.mist.slicing.utils.NodeHashSet;
import es.upv.mist.slicing.utils.StaticTypeSolver;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Slicer {
    protected static final String HELP_HEADER = "Java SDG Slicer: extract a slice from a Java program. At least" +
            " the \"-c\" flag must be used to specify the slicing criterion.";

    protected static final Pattern SC_PATTERN;
    protected static final File DEFAULT_OUTPUT_DIR = new File("./slice/");
    protected static final Options OPTIONS = new Options();

    static {
        String fileRe = "(?<file>[^#]+\\.java)";
        String lineRe = "(?<line>[1-9]\\d*)";
        String varsRe = "(?<var>[a-zA-Z_]\\w*(?:,[a-zA-Z_]\\w*)*)";
        SC_PATTERN = Pattern.compile(fileRe + "#" + lineRe + "(?::" + varsRe + ")?");
    }

    static {
        OPTIONS.addOption(Option
                .builder("f").longOpt("file")
                .hasArg().argName("CriterionFile.java").type(File.class)
                .desc("The file that contains the slicing criterion.")
                .build());
        OPTIONS.addOption(Option
                .builder("l").longOpt("line")
                .hasArg().argName("line-number").type(Number.class)
                .desc("The line that contains the statement of the slicing criterion.")
                .build());
        OPTIONS.addOption(Option
                .builder("v").longOpt("var")
                .hasArgs().argName("variable-name")
                .desc("The name of the variable of the slicing criterion. Not setting this option is" +
                        " equivalent to selecting all the code in the given line number.")
                .build());
        OPTIONS.addOption(Option
                .builder("c").longOpt("criterion")
                .hasArg().argName("file#line[:var]")
                .desc("The slicing criterion, in the format \"file#line:var\". The variable is optional."+
                        " This option may be replaced by \"-f\", \"-l\" and \"-v\"." +
                        " If this argument is set, it will override the individual ones.")
                .build());
        OPTIONS.addOption(Option
                .builder("i").longOpt("include")
                .hasArgs().argName("directory[,directory,...]").valueSeparator(',')
                .desc("Includes the directories listed in the search for methods called from the slicing criterion " +
                        "(directly or transitively). Methods that are not included here or part of the JRE, including" +
                        " third party libraries will not be analyzed, resulting in less precise slicing.")
                .build());
        OPTIONS.addOption(Option
                .builder("o").longOpt("output")
                .hasArg().argName("output-dir")
                .desc("The directory where the sliced source code should be placed. By default, it is placed at " +
                        DEFAULT_OUTPUT_DIR)
                .build());
        OPTIONS.addOption(Option
                .builder("t").longOpt("type")
                .hasArg().argName("graph-type")
                .desc("The type of graph to be built. Available options are SDG, ASDG, PSDG, ESSDG, JSysDG.")
                .build());
        OPTIONS.addOption(Option
                .builder("h").longOpt("help")
                .desc("Shows this text")
                .build());
        OPTIONS.addOption(Option
                .builder("a").longOpt("all")
                .desc("Slice all variables in the project and export to JSON")
                .build());
        OPTIONS.addOption(Option
                .builder("g").longOpt("dump-sdg")
                .desc("Dump the WHOLE-PROGRAM SDG (every node + every directed edge, edge"
                        + " types DATA/PARAM_IN/PARAM_OUT/RETURN/SUMMARY/CALL/CONTROL) to"
                        + " sdg_dump.json for the CQS client-closure substrate. Like -a it"
                        + " needs no criterion. Read-only: re-serializes the built SDG, adds"
                        + " no analysis.")
                .build());
        OPTIONS.addOption(Option
                .builder("j").longOpt("directed-json")
                .desc("With -c (directed criterion file#line[:var]), emit the SAME node/edge JSON schema as" +
                        " -a for the function containing the criterion line (rooted at the oracle statement" +
                        " line, NOT variable-definition points), instead of writing sliced source.")
                .build());
        OPTIONS.addOption(Option
                .builder("C").longOpt("criteria-file")
                .hasArg().argName("criteria.txt").type(File.class)
                .desc("Batch directed-JSON mode (with -j): a text file with one criterion per line in the" +
                        " format \"absFile#line[:var]\". Builds the SDG ONCE and slices every criterion," +
                        " emitting one JSON list. Avoids rebuilding the SDG per criterion.")
                .build());
        OPTIONS.addOption(Option
                .builder("p").longOpt("project")
                .hasArg().argName("project-name")
                .desc("The name of the project (used for EID generation in -a mode)")
                .build());
        OPTIONS.addOption(Option
                .builder("L").longOpt("classpath")
                .hasArg().argName("classpath-file-or-jars")
                .desc("DDSSR: a FILE containing a path-separator-separated classpath of dependency jars" +
                        " (e.g. the output of `mvn dependency:build-classpath`), OR an inline" +
                        " path-separator-separated list of jars. Each jar is registered as a JarTypeSolver so" +
                        " type resolution uses REAL dependency signatures, recovering cross-method call-graph" +
                        " edges that would otherwise be dropped as unresolved symbols (CG=0).")
                .build());
    }

    private final Set<File> dirIncludeSet = new HashSet<>();
    private final java.util.List<String> classpathJars = new java.util.ArrayList<>();
    private File outputDir = DEFAULT_OUTPUT_DIR;
    private File scFile;
    private int scLine;
    private String scVar;
    private final CommandLine cliOpts;

    public Slicer(String... cliArgs) throws ParseException {
        cliOpts = new DefaultParser().parse(OPTIONS, cliArgs);
        if (cliOpts.hasOption('h'))
            printHelp();
        
        if (cliOpts.hasOption('a') || cliOpts.hasOption('C') || cliOpts.hasOption('g')) {
            // In 'all' or batch criteria-file mode, we don't need a single criterion argument
            if (cliOpts.hasOption('a') && (cliOpts.hasOption('c') || (cliOpts.hasOption('f') && cliOpts.hasOption('l')))) {
                System.out.println("Warning: Slicing criterion arguments ignored in 'all' mode.");
            }
        } else {
            if (cliOpts.hasOption('c')) {
                Matcher matcher = SC_PATTERN.matcher(cliOpts.getOptionValue("criterion"));
                if (!matcher.matches())
                    throw new ParseException("Invalid format for slicing criterion, see --help for more details");
                setScFile(matcher.group("file"));
                setScLine(Integer.parseInt(matcher.group("line")));
                String var = matcher.group("var");
                if (var != null)
                    setScVar(var);
            } else if (cliOpts.hasOption('f') && cliOpts.hasOption('l')) {
                setScFile(cliOpts.getOptionValue('f'));
                setScLine(((Number) cliOpts.getParsedOptionValue("l")).intValue());
                if (cliOpts.hasOption('v'))
                    setScVar(cliOpts.getOptionValue('v'));
            } else {
                throw new ParseException("Slicing criterion not specified: either use \"-c\" or \"-f\" and \"-l\".");
            }
        }

        if (cliOpts.hasOption('o'))
            outputDir = new File(cliOpts.getOptionValue("o"));

        if (cliOpts.hasOption('i')) {
            for (String str : cliOpts.getOptionValues('i')) {
                File dir = new File(str);
                if (!dir.isDirectory())
                    throw new ParseException("One of the include directories is not a directory or isn't accesible: " + str);
                dirIncludeSet.add(dir);
            }
        }

        if (cliOpts.hasOption("classpath")) {
            String val = cliOpts.getOptionValue("classpath");
            String cp = val;
            File cpFile = new File(val);
            if (cpFile.isFile()) {
                try {
                    cp = new String(java.nio.file.Files.readAllBytes(cpFile.toPath())).trim();
                } catch (java.io.IOException e) {
                    throw new ParseException("Cannot read classpath file: " + val + " (" + e.getMessage() + ")");
                }
            }
            for (String jar : cp.split(java.io.File.pathSeparator)) {
                jar = jar.trim();
                if (!jar.isEmpty())
                    classpathJars.add(jar);
            }
        }
    }

    private void setScFile(String fileName) throws ParseException {
        File file = new File(fileName);
        if (!(file.exists() && file.isFile()))
            throw new ParseException("Slicing criterion file is not an existing file.");
        scFile = file;
    }

    private void setScLine(int line) throws ParseException {
        if (line <= 0)
            throw new ParseException("The line of the slicing criterion must be strictly greater than zero.");
        scLine = line;
    }

    private void setScVar(String scVar) {
        this.scVar = scVar;
    }

    public Set<File> getDirIncludeSet() {
        return Collections.unmodifiableSet(dirIncludeSet);
    }

    public File getOutputDir() {
        return outputDir;
    }

    public File getScFile() {
        return scFile;
    }

    public int getScLine() {
        return scLine;
    }

    public String getScVar() {
        return scVar;
    }

    public void slice() throws ParseException {
        // Configure JavaParser
        StaticJavaParser.getConfiguration().setAttributeComments(false);
        StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Configuring JavaParser");
        StaticTypeSolver.addTypeSolverJRE();
        for (File directory : dirIncludeSet)
            StaticTypeSolver.addTypeSolver(new JavaParserTypeSolver(directory));
        // DDSSR: register REAL dependency signatures so type resolution recovers cross-method CG.
        int cpOk = 0;
        for (String jar : classpathJars) {
            try {
                StaticTypeSolver.addTypeSolver(new JarTypeSolver(jar));
                cpOk++;
            } catch (Exception e) {
                System.err.println("WARN: cannot load jar typesolver (skipped): " + jar + " (" + e.getMessage() + ")");
            }
        }
        if (!classpathJars.isEmpty())
            Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO,
                    "DDSSR: registered " + cpOk + "/" + classpathJars.size() + " dependency jars for type resolution");

        // Build the SDG
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Parsing files");
        Set<CompilationUnit> units = new NodeHashSet<>();
        List<Problem> problems = new LinkedList<>();
        boolean scFileFound = false;
        for (File file : (Iterable<File>) findAllJavaFiles(dirIncludeSet)::iterator)
            scFileFound |= parse(file, units, problems);
        
        // In 'all'/batch/dump-sdg mode, we might not have scFile, but we need to parse all files in include dirs
        if (!cliOpts.hasOption('a') && !cliOpts.hasOption('C') && !cliOpts.hasOption('g') && !scFileFound)
            parse(scFile, units, problems);
            
        if (!problems.isEmpty()) {
            for (Problem p : problems)
                System.out.println(" * " + p.getVerboseMessage());
            if (cliOpts.hasOption('a')) {
                System.err.println("WARN: " + problems.size() + " file(s) had parse problems, skipping them in batch mode.");
            } else {
                throw new ParseException("Some problems were found while parsing files or folders");
            }
        }

        if (units.isEmpty()) {
            System.err.println("ERROR: No files were successfully parsed.");
            return;
        }

        SDG sdg;
        switch (cliOpts.getOptionValue("type", "JSysDG")) {
            case "SDG":    sdg = new SDG();    break;
            case "ASDG":   sdg = new ASDG();   break;
            case "PSDG":   sdg = new PSDG();   break;
            case "ESSDG":  sdg = new ESSDG();  break;
            case "JSysDG": sdg = new JSysDG(); break;
            default:
                throw new IllegalArgumentException("Unknown type of graph. Available graphs are SDG, ASDG, PSDG, ESSDG, JSysDG.");
        }
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Building the SDG");
        try {
            sdg.build(new NodeList<>(units));
        } catch (StackOverflowError e) {
            System.err.println("ERROR: StackOverflowError during SDG build. Code structure too deep.");
            if (cliOpts.hasOption('a')) {
                // In batch mode, output empty result rather than crashing
                outputEmptyResult();
                return;
            }
            throw new RuntimeException("StackOverflowError during SDG build", e);
        } catch (Exception e) {
            System.err.println("ERROR: SDG build failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            if (cliOpts.hasOption('a')) {
                outputEmptyResult();
                return;
            }
            throw new RuntimeException("SDG build failed", e);
        }

        if (cliOpts.hasOption('g')) {
            dumpSdg(sdg, units);
            return;
        }

        if (cliOpts.hasOption('a')) {
            sliceAll(sdg, units);
            return;
        }

        if (cliOpts.hasOption('j')) {
            sliceDirectedJson(sdg, units);
            return;
        }

        // Slice the SDG
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Searching for criterion and slicing");
        SlicingCriterion sc = new FileLineSlicingCriterion(scFile, scLine, scVar);
        Slice slice = sdg.slice(sc);

        // Convert the slice to code and output the result to `outputDir`
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Printing slice to files");
        for (CompilationUnit cu : slice.toAst()) {
            if (cu.getStorage().isEmpty())
                throw new IllegalStateException("A synthetic CompilationUnit was discovered, with no file associated to it.");
            Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Printing slice for " + cu.getStorage().get().getFileName());
            String packagePath = cu.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse("").replace(".", "/");
            File packageDir = new File(outputDir, packagePath);
            packageDir.mkdirs();
            File javaFile = new File(packageDir, cu.getStorage().get().getFileName());
            try (PrintWriter pw = new PrintWriter(javaFile)) {
                pw.print(new BlockComment(getDisclaimer(cu.getStorage().get())));
                pw.print(cu);
            } catch (FileNotFoundException e) {
                System.err.println("Could not write file " + javaFile);
            }
        }
    }

    /** Dump the WHOLE-PROGRAM SDG for the CQS client-closure substrate. Unlike -a
     *  (per-function, per-criterion, intra-function edges for GNN training), this
     *  walks sdg.edgeSet() ONCE and emits every node (keyed by the graph-unique
     *  GraphNode id) and every directed edge with a SOUND type label:
     *    value-carrying (the witness value can actually travel):
     *        DATA / PARAM_IN / PARAM_OUT / RETURN / SUMMARY
     *    control-only (forcing / activation ONLY, never proves value flow):
     *        CALL / CONTROL
     *  Endpoints are the long graph ids (always present); file/line/col/method are
     *  emitted per node so the Python adapter can map a (file,line) witness/
     *  criterion to its statement node (multivalued/missing -> the adapter
     *  abstains -> UNKNOWN). Read-only: builds nothing new, re-serializes the
     *  already-built SDG. */
    private void dumpSdg(SDG sdg, Set<CompilationUnit> units) {
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO,
                "Dumping whole-program SDG (CQS client-closure substrate)...");
        String projectName = cliOpts.getOptionValue("p", "unknown_project");

        Map<Long, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> edgeDedup = new HashSet<>();

        for (es.upv.mist.slicing.arcs.Arc arc : sdg.edgeSet()) {
            if (arc instanceof StructuralArc) continue;

            String type;
            if (arc.isDataDependencyArc()
                    || arc instanceof FlowDependencyArc
                    || arc instanceof es.upv.mist.slicing.arcs.pdg.ObjectFlowDependencyArc
                    || arc instanceof es.upv.mist.slicing.arcs.pdg.TotalDefinitionDependenceArc) {
                type = "DATA";
            } else if (arc instanceof es.upv.mist.slicing.arcs.sdg.ReturnArc) {
                type = "RETURN";
            } else if (arc.isParameterInOutArc()) {
                if (arc.isInterproceduralInputArc()) type = "PARAM_IN";
                else if (arc.isInterproceduralOutputArc()) type = "PARAM_OUT";
                else type = "PARAM";
            } else if (arc.isSummaryArc()) {
                type = "SUMMARY";
            } else if (arc.isCallArc()) {
                type = "CALL";
            } else if (arc.isControlDependencyArc() || arc.isControlFlowArc()) {
                type = "CONTROL";
            } else {
                type = "OTHER";
            }

            es.upv.mist.slicing.nodes.GraphNode<?> s =
                    (es.upv.mist.slicing.nodes.GraphNode<?>) sdg.getEdgeSource(arc);
            es.upv.mist.slicing.nodes.GraphNode<?> t =
                    (es.upv.mist.slicing.nodes.GraphNode<?>) sdg.getEdgeTarget(arc);
            long sid = s.getId();
            long tid = t.getId();
            if (sid == tid) continue;

            registerSdgNode(s, nodeMap);
            registerSdgNode(t, nodeMap);

            String dk = sid + "|" + tid + "|" + type;
            if (!edgeDedup.add(dk)) continue;

            Map<String, Object> e = new HashMap<>();
            e.put("src", sid);
            e.put("dst", tid);
            e.put("type", type);
            e.put("arc_class", arc.getClass().getSimpleName());
            String lbl = arc.getLabel();
            if (lbl != null && !lbl.isEmpty()) e.put("var", lbl);
            edges.add(e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project_name", projectName);
        out.put("graph_type", cliOpts.getOptionValue("type", "JSysDG"));
        out.put("n_nodes", nodeMap.size());
        out.put("n_edges", edges.size());
        out.put("nodes", new ArrayList<>(nodeMap.values()));
        out.put("edges", edges);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        outputDir.mkdirs();
        File jsonFile = new File(outputDir, "sdg_dump.json");
        try (FileWriter writer = new FileWriter(jsonFile)) {
            gson.toJson(out, writer);
            Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO,
                    "Wrote SDG dump: " + nodeMap.size() + " nodes, " + edges.size()
                            + " edges -> " + jsonFile);
        } catch (IOException ex) {
            System.err.println("ERROR writing SDG dump: " + ex.getMessage());
        }
    }

    /** Register one SDG node (by graph-unique id) with its source coordinates and
     *  enclosing method signature, for the adapter's (file,line) -> node mapping.
     *  Synthetic / IO nodes with no AST position get only an id; they still
     *  participate in graph connectivity via their id but are never used as a
     *  mapping target. */
    private void registerSdgNode(es.upv.mist.slicing.nodes.GraphNode<?> node,
                                 Map<Long, Map<String, Object>> nodeMap) {
        long id = node.getId();
        if (nodeMap.containsKey(id)) return;
        Map<String, Object> rec = new HashMap<>();
        rec.put("id", id);
        rec.put("graph_node_class", node.getClass().getSimpleName());
        com.github.javaparser.ast.Node ast = node.getAstNode();
        if (ast != null) {
            if (ast.getBegin().isPresent()) {
                rec.put("line", ast.getBegin().get().line);
                rec.put("col", ast.getBegin().get().column);
            }
            ast.getEnd().ifPresent(p -> rec.put("end_line", p.line));
            Optional<CompilationUnit> cu = ast.findAncestor(CompilationUnit.class);
            if (cu.isPresent() && cu.get().getStorage().isPresent()) {
                rec.put("file", relativizeToIncludes(
                        cu.get().getStorage().get().getPath().toString()));
            }
            Optional<CallableDeclaration> cd = ast.findAncestor(CallableDeclaration.class);
            if (cd.isPresent()) {
                rec.put("method", callableSignature(cd.get()));
                if (cd.get().getParentNode().isPresent()
                        && cd.get().getParentNode().get() instanceof com.github.javaparser.ast.body.TypeDeclaration) {
                    rec.put("class", ((com.github.javaparser.ast.body.TypeDeclaration<?>)
                            cd.get().getParentNode().get()).getNameAsString());
                }
            }
            rec.put("kind", ast.getClass().getSimpleName());
            String code = ast.toString();
            int nl = code.indexOf('\n');
            rec.put("code", nl > 0 ? code.substring(0, nl) : code);
        }
        nodeMap.put(id, rec);
    }

    private String relativizeToIncludes(String absPath) {
        for (File includeDir : dirIncludeSet) {
            java.nio.file.Path inc = includeDir.getAbsoluteFile().toPath().normalize();
            java.nio.file.Path f = new File(absPath).getAbsoluteFile().toPath().normalize();
            if (f.startsWith(inc)) return inc.relativize(f).toString();
        }
        return absPath;
    }

    private String callableSignature(CallableDeclaration cd) {
        StringBuilder sb = new StringBuilder(cd.getNameAsString()).append("(");
        for (int i = 0; i < cd.getParameters().size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(cd.getParameter(i).getType().asString());
        }
        return sb.append(")").toString();
    }

    private void sliceAll(SDG sdg, Set<CompilationUnit> units) {
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Slicing all variables...");
        List<Map<String, Object>> results = new ArrayList<>();
        String projectName = cliOpts.getOptionValue("p", "unknown_project");

        for (CompilationUnit cu : units) {
            if (cu.getStorage().isEmpty()) continue;
            String filePath = cu.getStorage().get().getPath().toString();
            String fileName = cu.getStorage().get().getFileName();
            
            // Try to make path relative to project root if possible, or just use filename for hash stability?
            // User said "project name + file + variable". 
            // We'll use the file name as it appears in the storage (absolute path usually).
            // To be safe across environments, maybe we should try to relativize against include dirs?
            // But for now, let's use the full path or just filename if that's what "file" implies.
            // Given "project name + file", usually implies relative path within project.
            // Let's try to find the relative path from the include directories.
            
            String relativePath = filePath;
            for (File includeDir : dirIncludeSet) {
                java.nio.file.Path includePath = includeDir.getAbsoluteFile().toPath().normalize();
                java.nio.file.Path absFilePath = new File(filePath).getAbsoluteFile().toPath().normalize();
                if (absFilePath.startsWith(includePath)) {
                    relativePath = includePath.relativize(absFilePath).toString();
                    break;
                }
            }

            List<CallableDeclaration> callables = cu.findAll(CallableDeclaration.class);
            for (CallableDeclaration callable : callables) {
                try {
                    String functionName = callable.getNameAsString();
                    String className = "";
                    if (callable.getParentNode().isPresent() && callable.getParentNode().get() instanceof com.github.javaparser.ast.body.TypeDeclaration) {
                        className = ((com.github.javaparser.ast.body.TypeDeclaration<?>) callable.getParentNode().get()).getNameAsString();
                    }

                    String functionCode = callable.toString();
                    List<Map<String, Object>> slices = new ArrayList<>();

                    // Find variables
                    List<VariableDeclarator> vars = callable.findAll(VariableDeclarator.class);
                    // Also parameters
                    List<Parameter> params = callable.findAll(Parameter.class);

                    List<com.github.javaparser.ast.Node> allVars = new ArrayList<>();
                    allVars.addAll(vars);
                    allVars.addAll(params);

                    // --- Hoist statements outside allVars loop (invariant across variables) ---
                    List<Statement> statements = callable.findAll(Statement.class);

                    // Build position -> nodeId mappings (invariant across variables)
                    // posToNodeId: exact "line:col" key (handles same-line multi-statement)
                    // lineToNodeId: line-only fallback (for SDG nodes that are inner expressions)
                    Map<String, String> posToNodeId = new HashMap<>();
                    Map<Integer, String> lineToNodeId = new HashMap<>();
                    for (Statement stmt : statements) {
                        if (!stmt.getBegin().isPresent()) continue;
                        com.github.javaparser.Position pos = stmt.getBegin().get();
                        String nodeId = pos.line + ":" + pos.column;
                        posToNodeId.put(nodeId, nodeId);
                        lineToNodeId.putIfAbsent(pos.line, nodeId);
                    }

                    // --- SDG edge export (once per function) ---
                    Set<String> edgeDedup = new HashSet<>();
                    List<Map<String, Object>> functionEdges = new ArrayList<>();

                    for (es.upv.mist.slicing.arcs.Arc arc : sdg.edgeSet()) {
                        if (arc instanceof StructuralArc) continue;

                        // Map arc type -> {DFG, CFG, CG}; skip inter-procedural arcs
                        String edgeType;
                        if (arc.isDataDependencyArc() || arc instanceof FlowDependencyArc) {
                            edgeType = "DFG";
                        } else if (arc.isControlFlowArc() || arc.isControlDependencyArc()) {
                            edgeType = "CFG";
                        } else if (arc.isCallArc()) {
                            edgeType = "CG";
                        } else {
                            continue;  // Skip ParameterInOut, Return, Summary arcs (inter-procedural noise)
                        }

                        // Get source/target positions
                        es.upv.mist.slicing.nodes.GraphNode<?> srcNode = sdg.getEdgeSource(arc);
                        es.upv.mist.slicing.nodes.GraphNode<?> tgtNode = sdg.getEdgeTarget(arc);

                        if (srcNode.getAstNode() == null || tgtNode.getAstNode() == null) continue;
                        if (!srcNode.getAstNode().getBegin().isPresent() ||
                            !tgtNode.getAstNode().getBegin().isPresent()) continue;

                        int srcLine = srcNode.getAstNode().getBegin().get().line;
                        int srcCol = srcNode.getAstNode().getBegin().get().column;
                        int tgtLine = tgtNode.getAstNode().getBegin().get().line;
                        int tgtCol = tgtNode.getAstNode().getBegin().get().column;

                        // Both endpoints must belong to this function's statements
                        // Try exact line:col match first, fall back to line-only
                        String srcId = posToNodeId.getOrDefault(srcLine + ":" + srcCol, lineToNodeId.get(srcLine));
                        String tgtId = posToNodeId.getOrDefault(tgtLine + ":" + tgtCol, lineToNodeId.get(tgtLine));
                        if (srcId == null || tgtId == null) continue;
                        if (srcId.equals(tgtId)) continue;  // skip self-loops

                        String dedupKey = srcId + "|" + tgtId + "|" + edgeType;
                        if (!edgeDedup.add(dedupKey)) continue;

                        Map<String, Object> edgeInfo = new HashMap<>();
                        edgeInfo.put("src", srcId);
                        edgeInfo.put("dst", tgtId);
                        edgeInfo.put("type", edgeType);
                        if (arc.isDataDependencyArc() && arc.getLabel() != null) {
                            edgeInfo.put("label", arc.getLabel());
                        }
                        functionEdges.add(edgeInfo);
                    }

                    // --- Cross-function CG: add callee stub nodes ---
                    // CallArcs where src is in this function but dst (callee) is external.
                    // Create stub nodes (type="function") so CG edges are preserved.
                    Map<String, Map<String, Object>> calleeStubMap = new LinkedHashMap<>();
                    for (es.upv.mist.slicing.arcs.Arc arc : sdg.edgeSet()) {
                        if (!arc.isCallArc()) continue;

                        es.upv.mist.slicing.nodes.GraphNode<?> srcNode = sdg.getEdgeSource(arc);
                        es.upv.mist.slicing.nodes.GraphNode<?> tgtNode = sdg.getEdgeTarget(arc);

                        if (srcNode.getAstNode() == null || tgtNode.getAstNode() == null) continue;
                        if (!srcNode.getAstNode().getBegin().isPresent() ||
                            !tgtNode.getAstNode().getBegin().isPresent()) continue;

                        int srcLine = srcNode.getAstNode().getBegin().get().line;
                        int srcCol = srcNode.getAstNode().getBegin().get().column;
                        String srcId = posToNodeId.getOrDefault(srcLine + ":" + srcCol, lineToNodeId.get(srcLine));
                        if (srcId == null) continue;  // src not in this function

                        int tgtLine = tgtNode.getAstNode().getBegin().get().line;
                        int tgtCol = tgtNode.getAstNode().getBegin().get().column;
                        String tgtIdLocal = posToNodeId.getOrDefault(tgtLine + ":" + tgtCol, lineToNodeId.get(tgtLine));
                        if (tgtIdLocal != null) continue;  // both in function, already handled above

                        // External callee — create stub node
                        String calleeId = "cg_" + tgtLine + "_" + tgtCol;

                        String cgDedupKey = srcId + "|" + calleeId + "|CG";
                        if (!edgeDedup.add(cgDedupKey)) continue;

                        // Add CG edge
                        Map<String, Object> cgEdge = new HashMap<>();
                        cgEdge.put("src", srcId);
                        cgEdge.put("dst", calleeId);
                        cgEdge.put("type", "CG");
                        functionEdges.add(cgEdge);

                        // Build callee stub (dedup)
                        if (!calleeStubMap.containsKey(calleeId)) {
                            Map<String, Object> stub = new HashMap<>();
                            stub.put("id", calleeId);
                            stub.put("line", tgtLine);
                            stub.put("col_offset", tgtCol);
                            stub.put("start_line", tgtLine);
                            stub.put("type", "function");
                            // First line of callee code (method signature)
                            String calleeCode = tgtNode.getAstNode().toString();
                            int nlIdx = calleeCode.indexOf('\n');
                            if (nlIdx > 0) calleeCode = calleeCode.substring(0, nlIdx);
                            stub.put("code", calleeCode);
                            // Callee source file
                            String calleeFilePath = relativePath;
                            Optional<CompilationUnit> calleeCu =
                                tgtNode.getAstNode().findAncestor(CompilationUnit.class);
                            if (calleeCu.isPresent() && calleeCu.get().getStorage().isPresent()) {
                                String absPath = calleeCu.get().getStorage().get().getPath().toString();
                                for (File includeDir : dirIncludeSet) {
                                    java.nio.file.Path incPath = includeDir.getAbsoluteFile().toPath().normalize();
                                    java.nio.file.Path absFilePath = new File(absPath).getAbsoluteFile().toPath().normalize();
                                    if (absFilePath.startsWith(incPath)) {
                                        calleeFilePath = incPath.relativize(absFilePath).toString();
                                        break;
                                    }
                                }
                            }
                            stub.put("source_file", calleeFilePath);
                            calleeStubMap.put(calleeId, stub);
                        }
                    }

                    for (com.github.javaparser.ast.Node varNode : allVars) {
                        String varName = "";
                        int line = -1;
                        if (varNode instanceof VariableDeclarator) {
                            varName = ((VariableDeclarator) varNode).getNameAsString();
                            line = ((VariableDeclarator) varNode).getBegin().map(p -> p.line).orElse(-1);
                        } else if (varNode instanceof Parameter) {
                            varName = ((Parameter) varNode).getNameAsString();
                            line = ((Parameter) varNode).getBegin().map(p -> p.line).orElse(-1);
                        }

                        if (line == -1) continue;

                        try {
                            SlicingCriterion sc = new FileLineSlicingCriterion(new File(filePath), line, varName);

                            // --- Backward slice (existing logic, unchanged) ---
                            Slice bwdSlice = sdg.slice(sc);

                            List<Map<String, Object>> nodesData = new ArrayList<>();

                            // Build a set of backward-sliced line numbers
                            Set<Integer> bwdSlicedLines = new HashSet<>();
                            for (es.upv.mist.slicing.nodes.GraphNode<?> gn : bwdSlice.getGraphNodes()) {
                                if (gn.getAstNode() != null && gn.getAstNode().getBegin().isPresent()) {
                                    bwdSlicedLines.add(gn.getAstNode().getBegin().get().line);
                                }
                            }
                            // Skip empty slices (method's CFG was not built due to unresolved symbols)
                            if (bwdSlicedLines.isEmpty()) continue;

                            // --- Forward slice (classic 2-pass on the same SDG, reversed direction) ---
                            Set<Integer> fwdSlicedLines = new HashSet<>();
                            try {
                                SlicingCriterion fwdSc = new FileLineSlicingCriterion(new File(filePath), line, varName);
                                Set<es.upv.mist.slicing.nodes.GraphNode<?>> criterionNodes = fwdSc.findNode(sdg);
                                ForwardClassicSlicingAlgorithm fwdAlgo = new ForwardClassicSlicingAlgorithm(sdg);
                                Slice fwdSlice = fwdAlgo.traverse(criterionNodes);
                                for (es.upv.mist.slicing.nodes.GraphNode<?> gn : fwdSlice.getGraphNodes()) {
                                    if (gn.getAstNode() != null && gn.getAstNode().getBegin().isPresent()) {
                                        fwdSlicedLines.add(gn.getAstNode().getBegin().get().line);
                                    }
                                }
                            } catch (Exception e) {
                                System.err.println("WARN: Forward slicing failed for " + varName + " at line " + line + ": " + e.getMessage());
                            }

                            for (Statement stmt : statements) {
                                if (!stmt.getBegin().isPresent()) continue;

                                com.github.javaparser.Position stmtPos = stmt.getBegin().get();
                                boolean inBwdSlice = bwdSlicedLines.contains(stmtPos.line);
                                boolean inFwdSlice = fwdSlicedLines.contains(stmtPos.line);

                                Map<String, Object> nodeInfo = new HashMap<>();
                                nodeInfo.put("line", stmtPos.line);
                                nodeInfo.put("code", stmt.toString());
                                nodeInfo.put("y_fwd", inFwdSlice ? 1 : 0);
                                nodeInfo.put("y_bwd", inBwdSlice ? 1 : 0);
                                nodeInfo.put("id", stmtPos.line + ":" + stmtPos.column);

                                // New fields for direct dataloader consumption
                                nodeInfo.put("type", classifyNodeType(stmt));
                                nodeInfo.put("start_line", stmtPos.line);
                                stmt.getEnd().ifPresent(end -> nodeInfo.put("end_line", end.line));
                                nodeInfo.put("col_offset", stmtPos.column);
                                nodeInfo.put("source_file", relativePath);
                                String stmtVar = extractVariable(stmt);
                                if (stmtVar != null) nodeInfo.put("variable", stmtVar);

                                nodesData.add(nodeInfo);
                            }

                            // Add callee stub nodes for cross-function CG edges
                            for (Map<String, Object> stub : calleeStubMap.values()) {
                                Map<String, Object> calleeNode = new HashMap<>(stub);
                                calleeNode.put("y_fwd", 0);
                                calleeNode.put("y_bwd", 0);
                                nodesData.add(calleeNode);
                            }

                            Map<String, Object> sliceData = new HashMap<>();
                            Map<String, Object> criterion = new HashMap<>();
                            criterion.put("variable", varName);
                            criterion.put("line", line);
                            sliceData.put("slice_criterion", criterion);
                            sliceData.put("nodes", nodesData);
                            sliceData.put("edges", functionEdges);

                            slices.add(sliceData);

                        } catch (Exception e) {
                            System.err.println("Error slicing variable " + varName + " at line " + line + ": " + e.getMessage());
                        }
                    }

                    if (!slices.isEmpty()) {
                        StringBuilder sigBuilder = new StringBuilder("(");
                        for (int pi = 0; pi < callable.getParameters().size(); pi++) {
                            if (pi > 0) sigBuilder.append(",");
                            sigBuilder.append(callable.getParameter(pi).getType().asString());
                        }
                        sigBuilder.append(")");
                        String uniqueString = projectName + "|" + relativePath + "|" + functionName + "|" + sigBuilder.toString();
                        String eid = generateHash(uniqueString);

                        Map<String, Object> funcResult = new HashMap<>();
                        funcResult.put("eid", eid);
                        funcResult.put("project_name", projectName);
                        funcResult.put("function_name", functionName);
                        funcResult.put("class_name", className);
                        funcResult.put("file_name", fileName);
                        funcResult.put("language", "java");
                        funcResult.put("function_code", functionCode);
                        // Build line-numbered version from original source file
                        if (cu.getStorage().isPresent()) {
                            try {
                                java.nio.file.Path sourcePath = cu.getStorage().get().getPath();
                                List<String> sourceLines = java.nio.file.Files.readAllLines(sourcePath);
                                int startLine = callable.getBegin().isPresent()
                                    ? ((com.github.javaparser.Position) callable.getBegin().get()).line : 1;
                                int endLine = callable.getEnd().isPresent()
                                    ? ((com.github.javaparser.Position) callable.getEnd().get()).line : sourceLines.size();
                                StringBuilder aligned = new StringBuilder();
                                for (int i = startLine; i <= Math.min(endLine, sourceLines.size()); i++) {
                                    if (i > startLine) aligned.append("\n");
                                    aligned.append(i).append("| ").append(sourceLines.get(i - 1));
                                }
                                funcResult.put("function_code_aligned", aligned.toString());
                            } catch (Exception ignored) {
                                // No aligned code available
                            }
                        }
                        funcResult.put("slices", slices);
                        results.add(funcResult);
                    }
                } catch (StackOverflowError e) {
                    System.err.println("ERROR: StackOverflowError processing function " + callable.getNameAsString() + " in " + fileName + ", skipping.");
                } catch (Throwable t) {
                    System.err.println("ERROR: Failed to process function " + callable.getNameAsString() + " in " + fileName + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
                }
            }
        }

        // Export to JSON
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        File jsonFile = new File(outputDir, "slicing_result.json");
        outputDir.mkdirs();
        try (FileWriter writer = new FileWriter(jsonFile)) {
            gson.toJson(results, writer);
            Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Exported JSON to " + jsonFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Relativize an absolute file path against the include dirs (same logic sliceAll inlines). */
    private String relativizePath(String filePath) {
        for (File includeDir : dirIncludeSet) {
            java.nio.file.Path inc = includeDir.getAbsoluteFile().toPath().normalize();
            java.nio.file.Path abs = new File(filePath).getAbsoluteFile().toPath().normalize();
            if (abs.startsWith(inc)) return inc.relativize(abs).toString();
        }
        return filePath;
    }

    /** DIRECTED-JSON mode (-c ... -j): emit the SAME per-function node/edge JSON schema as sliceAll, but
     *  rooted at the oracle criterion (scFile#scLine[:scVar]) — i.e. the criterion line is the oracle
     *  STATEMENT line, not a variable-definition point. Only the function CONTAINING scLine is emitted
     *  (matching the GNN's per-function training unit). A top-level `all_slice_nodes` records every
     *  (source_file,line,y_bwd,y_fwd) in the FULL interprocedural backward/forward slice, so callers can
     *  separate graph VISIBILITY (can the slice even contain the witness) from MODEL SELECTION. */
    /** Driver: build the criteria list (single -c, or batch -C file) and emit one JSON list. The SDG is
     *  built ONCE by the caller, so batch mode slices every oracle criterion without rebuilding it. */
    private void sliceDirectedJson(SDG sdg, Set<CompilationUnit> units) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<File> cFiles = new ArrayList<>();
        List<Integer> cLines = new ArrayList<>();
        List<String> cVars = new ArrayList<>();
        if (cliOpts.hasOption('C')) {
            File critFile = new File(cliOpts.getOptionValue('C'));
            try {
                for (String ln : java.nio.file.Files.readAllLines(critFile.toPath())) {
                    ln = ln.trim();
                    if (ln.isEmpty() || ln.startsWith("#")) continue;
                    Matcher m = SC_PATTERN.matcher(ln);
                    if (!m.matches()) { System.err.println("DIRECTED: bad criterion line: " + ln); continue; }
                    cFiles.add(new File(m.group("file")));
                    cLines.add(Integer.parseInt(m.group("line")));
                    cVars.add(m.group("var"));  // may be null
                }
            } catch (IOException e) {
                System.err.println("DIRECTED: cannot read criteria file " + critFile + ": " + e.getMessage());
            }
        } else {
            cFiles.add(scFile); cLines.add(scLine); cVars.add(scVar);
        }
        for (int i = 0; i < cFiles.size(); i++) {
            try {
                results.addAll(directedSliceOne(sdg, units, cFiles.get(i), cLines.get(i), cVars.get(i)));
            } catch (Throwable t) {
                System.err.println("DIRECTED: criterion " + cFiles.get(i).getName() + "#" + cLines.get(i)
                        + " failed: " + t.getMessage());
            }
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        File jsonFile = new File(outputDir, "slicing_result.json");
        outputDir.mkdirs();
        try (FileWriter writer = new FileWriter(jsonFile)) {
            gson.toJson(results, writer);
            Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "DIRECTED: exported "
                    + results.size() + " function-result(s) for " + cFiles.size() + " criterion(s) to " + jsonFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Directed slice for ONE criterion (critFile#critLine[:critVar]) -> per-function results in the -a
     *  schema, rooted at the oracle statement line. Returns the function-result list (empty if the line
     *  is not inside any method or the slice is empty). */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> directedSliceOne(SDG sdg, Set<CompilationUnit> units, File scFile, int scLine, String scVar) {
        String projectName = cliOpts.getOptionValue("p", "unknown_project");
        List<Map<String, Object>> results = new ArrayList<>();

        // --- Slice ONCE at the oracle criterion (scVar may be null => whole line) ---
        Set<Integer> bwdSlicedLines = new HashSet<>();
        Set<Integer> fwdSlicedLines = new HashSet<>();
        List<Map<String, Object>> allSliceNodes = new ArrayList<>();
        boolean sliceOk = false;
        try {
            SlicingCriterion sc = new FileLineSlicingCriterion(scFile, scLine, scVar);
            Slice bwdSlice = sdg.slice(sc);
            for (es.upv.mist.slicing.nodes.GraphNode<?> gn : bwdSlice.getGraphNodes())
                if (gn.getAstNode() != null && gn.getAstNode().getBegin().isPresent())
                    bwdSlicedLines.add(gn.getAstNode().getBegin().get().line);
            Set<es.upv.mist.slicing.nodes.GraphNode<?>> critNodes = sc.findNode(sdg);
            Slice fwdSlice = new ForwardClassicSlicingAlgorithm(sdg).traverse(critNodes);
            for (es.upv.mist.slicing.nodes.GraphNode<?> gn : fwdSlice.getGraphNodes())
                if (gn.getAstNode() != null && gn.getAstNode().getBegin().isPresent())
                    fwdSlicedLines.add(gn.getAstNode().getBegin().get().line);
            // interprocedural visibility ceiling: every node across the whole bwd/fwd slice
            Set<es.upv.mist.slicing.nodes.GraphNode<?>> all = new HashSet<>();
            all.addAll(bwdSlice.getGraphNodes());
            all.addAll(fwdSlice.getGraphNodes());
            Set<String> seen = new HashSet<>();
            for (es.upv.mist.slicing.nodes.GraphNode<?> gn : all) {
                if (gn.getAstNode() == null || !gn.getAstNode().getBegin().isPresent()) continue;
                int ln = gn.getAstNode().getBegin().get().line;
                String sf = "";
                Optional<CompilationUnit> ncu = gn.getAstNode().findAncestor(CompilationUnit.class);
                if (ncu.isPresent() && ncu.get().getStorage().isPresent())
                    sf = relativizePath(ncu.get().getStorage().get().getPath().toString());
                if (!seen.add(sf + "#" + ln)) continue;
                Map<String, Object> m = new HashMap<>();
                m.put("source_file", sf);
                m.put("line", ln);
                m.put("y_bwd", bwdSlicedLines.contains(ln) ? 1 : 0);
                m.put("y_fwd", fwdSlicedLines.contains(ln) ? 1 : 0);
                allSliceNodes.add(m);
            }
            sliceOk = true;
        } catch (Exception e) {
            System.err.println("DIRECTED: slicing failed at " + scFile.getName() + "#" + scLine + ": " + e.getMessage());
        }

        // --- Emit the function CONTAINING scLine, in the -a per-function schema ---
        for (CompilationUnit cu : units) {
            if (cu.getStorage().isEmpty()) continue;
            java.nio.file.Path cuPath = cu.getStorage().get().getPath().toAbsolutePath().normalize();
            if (!cuPath.equals(scFile.getAbsoluteFile().toPath().normalize())) continue;
            String filePath = cu.getStorage().get().getPath().toString();
            String fileName = cu.getStorage().get().getFileName();
            String relativePath = relativizePath(filePath);

            for (CallableDeclaration callable : (List<CallableDeclaration>) cu.findAll(CallableDeclaration.class)) {
                if (!callable.getBegin().isPresent() || !callable.getEnd().isPresent()) continue;
                if (scLine < ((com.github.javaparser.Position) callable.getBegin().get()).line
                        || scLine > ((com.github.javaparser.Position) callable.getEnd().get()).line) continue;
                try {
                    String functionName = callable.getNameAsString();
                    String className = "";
                    if (callable.getParentNode().isPresent() && callable.getParentNode().get() instanceof com.github.javaparser.ast.body.TypeDeclaration)
                        className = ((com.github.javaparser.ast.body.TypeDeclaration<?>) callable.getParentNode().get()).getNameAsString();
                    String functionCode = callable.toString();

                    List<Statement> statements = callable.findAll(Statement.class);
                    Map<String, String> posToNodeId = new HashMap<>();
                    Map<Integer, String> lineToNodeId = new HashMap<>();
                    for (Statement stmt : statements) {
                        if (!stmt.getBegin().isPresent()) continue;
                        com.github.javaparser.Position pos = stmt.getBegin().get();
                        String nodeId = pos.line + ":" + pos.column;
                        posToNodeId.put(nodeId, nodeId);
                        lineToNodeId.putIfAbsent(pos.line, nodeId);
                    }

                    // edges (DFG/CFG/CG intra) — identical filter to sliceAll
                    Set<String> edgeDedup = new HashSet<>();
                    List<Map<String, Object>> functionEdges = new ArrayList<>();
                    for (es.upv.mist.slicing.arcs.Arc arc : sdg.edgeSet()) {
                        if (arc instanceof StructuralArc) continue;
                        String edgeType;
                        if (arc.isDataDependencyArc() || arc instanceof FlowDependencyArc) edgeType = "DFG";
                        else if (arc.isControlFlowArc() || arc.isControlDependencyArc()) edgeType = "CFG";
                        else if (arc.isCallArc()) edgeType = "CG";
                        else continue;
                        es.upv.mist.slicing.nodes.GraphNode<?> srcNode = sdg.getEdgeSource(arc);
                        es.upv.mist.slicing.nodes.GraphNode<?> tgtNode = sdg.getEdgeTarget(arc);
                        if (srcNode.getAstNode() == null || tgtNode.getAstNode() == null) continue;
                        if (!srcNode.getAstNode().getBegin().isPresent() || !tgtNode.getAstNode().getBegin().isPresent()) continue;
                        int srcLine = srcNode.getAstNode().getBegin().get().line, srcCol = srcNode.getAstNode().getBegin().get().column;
                        int tgtLine = tgtNode.getAstNode().getBegin().get().line, tgtCol = tgtNode.getAstNode().getBegin().get().column;
                        String srcId = posToNodeId.getOrDefault(srcLine + ":" + srcCol, lineToNodeId.get(srcLine));
                        String tgtId = posToNodeId.getOrDefault(tgtLine + ":" + tgtCol, lineToNodeId.get(tgtLine));
                        if (srcId == null || tgtId == null || srcId.equals(tgtId)) continue;
                        if (!edgeDedup.add(srcId + "|" + tgtId + "|" + edgeType)) continue;
                        Map<String, Object> edgeInfo = new HashMap<>();
                        edgeInfo.put("src", srcId); edgeInfo.put("dst", tgtId); edgeInfo.put("type", edgeType);
                        if (arc.isDataDependencyArc() && arc.getLabel() != null) edgeInfo.put("label", arc.getLabel());
                        functionEdges.add(edgeInfo);
                    }
                    // cross-function CG stubs (identical to sliceAll)
                    Map<String, Map<String, Object>> calleeStubMap = new LinkedHashMap<>();
                    for (es.upv.mist.slicing.arcs.Arc arc : sdg.edgeSet()) {
                        if (!arc.isCallArc()) continue;
                        es.upv.mist.slicing.nodes.GraphNode<?> srcNode = sdg.getEdgeSource(arc);
                        es.upv.mist.slicing.nodes.GraphNode<?> tgtNode = sdg.getEdgeTarget(arc);
                        if (srcNode.getAstNode() == null || tgtNode.getAstNode() == null) continue;
                        if (!srcNode.getAstNode().getBegin().isPresent() || !tgtNode.getAstNode().getBegin().isPresent()) continue;
                        int srcLine = srcNode.getAstNode().getBegin().get().line, srcCol = srcNode.getAstNode().getBegin().get().column;
                        String srcId = posToNodeId.getOrDefault(srcLine + ":" + srcCol, lineToNodeId.get(srcLine));
                        if (srcId == null) continue;
                        int tgtLine = tgtNode.getAstNode().getBegin().get().line, tgtCol = tgtNode.getAstNode().getBegin().get().column;
                        if (posToNodeId.getOrDefault(tgtLine + ":" + tgtCol, lineToNodeId.get(tgtLine)) != null) continue;
                        String calleeId = "cg_" + tgtLine + "_" + tgtCol;
                        if (!edgeDedup.add(srcId + "|" + calleeId + "|CG")) continue;
                        Map<String, Object> cgEdge = new HashMap<>();
                        cgEdge.put("src", srcId); cgEdge.put("dst", calleeId); cgEdge.put("type", "CG");
                        functionEdges.add(cgEdge);
                        if (!calleeStubMap.containsKey(calleeId)) {
                            Map<String, Object> stub = new HashMap<>();
                            stub.put("id", calleeId); stub.put("line", tgtLine); stub.put("col_offset", tgtCol);
                            stub.put("start_line", tgtLine); stub.put("type", "function");
                            String calleeCode = tgtNode.getAstNode().toString();
                            int nlIdx = calleeCode.indexOf('\n');
                            if (nlIdx > 0) calleeCode = calleeCode.substring(0, nlIdx);
                            stub.put("code", calleeCode);
                            String calleeFilePath = relativePath;
                            Optional<CompilationUnit> calleeCu = tgtNode.getAstNode().findAncestor(CompilationUnit.class);
                            if (calleeCu.isPresent() && calleeCu.get().getStorage().isPresent())
                                calleeFilePath = relativizePath(calleeCu.get().getStorage().get().getPath().toString());
                            stub.put("source_file", calleeFilePath);
                            calleeStubMap.put(calleeId, stub);
                        }
                    }

                    // nodes: this function's statements, labelled by the criterion's bwd/fwd slice
                    List<Map<String, Object>> nodesData = new ArrayList<>();
                    for (Statement stmt : statements) {
                        if (!stmt.getBegin().isPresent()) continue;
                        com.github.javaparser.Position stmtPos = stmt.getBegin().get();
                        Map<String, Object> nodeInfo = new HashMap<>();
                        nodeInfo.put("line", stmtPos.line);
                        nodeInfo.put("code", stmt.toString());
                        nodeInfo.put("y_fwd", fwdSlicedLines.contains(stmtPos.line) ? 1 : 0);
                        nodeInfo.put("y_bwd", bwdSlicedLines.contains(stmtPos.line) ? 1 : 0);
                        nodeInfo.put("id", stmtPos.line + ":" + stmtPos.column);
                        nodeInfo.put("type", classifyNodeType(stmt));
                        nodeInfo.put("start_line", stmtPos.line);
                        stmt.getEnd().ifPresent(end -> nodeInfo.put("end_line", end.line));
                        nodeInfo.put("col_offset", stmtPos.column);
                        nodeInfo.put("source_file", relativePath);
                        String stmtVar = extractVariable(stmt);
                        if (stmtVar != null) nodeInfo.put("variable", stmtVar);
                        nodesData.add(nodeInfo);
                    }
                    for (Map<String, Object> stub : calleeStubMap.values()) {
                        Map<String, Object> calleeNode = new HashMap<>(stub);
                        calleeNode.put("y_fwd", 0); calleeNode.put("y_bwd", 0);
                        nodesData.add(calleeNode);
                    }

                    Map<String, Object> sliceData = new HashMap<>();
                    Map<String, Object> criterion = new HashMap<>();
                    criterion.put("variable", scVar == null ? "" : scVar);
                    criterion.put("line", scLine);  // ROOTED AT ORACLE LINE, not a variable-def line
                    sliceData.put("slice_criterion", criterion);
                    sliceData.put("nodes", nodesData);
                    sliceData.put("edges", functionEdges);

                    Map<String, Object> funcResult = new HashMap<>();
                    funcResult.put("eid", generateHash(projectName + "|" + relativePath + "|" + functionName + "|" + scLine));
                    funcResult.put("project_name", projectName);
                    funcResult.put("function_name", functionName);
                    funcResult.put("class_name", className);
                    funcResult.put("file_name", fileName);
                    funcResult.put("language", "java");
                    funcResult.put("function_code", functionCode);
                    funcResult.put("criterion_line", scLine);
                    funcResult.put("criterion_file", relativePath);
                    funcResult.put("slice_ok", sliceOk);
                    funcResult.put("all_slice_nodes", allSliceNodes);
                    List<Map<String, Object>> slices = new ArrayList<>();
                    slices.add(sliceData);
                    funcResult.put("slices", slices);
                    results.add(funcResult);
                } catch (Throwable t) {
                    System.err.println("DIRECTED: failed function " + callable.getNameAsString() + ": " + t.getMessage());
                }
            }
        }
        return results;
    }

    private void outputEmptyResult() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        File jsonFile = new File(outputDir, "slicing_result.json");
        outputDir.mkdirs();
        try (FileWriter writer = new FileWriter(jsonFile)) {
            gson.toJson(new ArrayList<>(), writer);
            Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).log(Level.INFO, "Exported empty JSON to " + jsonFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String generateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (int i = 0; i < encodedhash.length; i++) {
                String hex = Integer.toHexString(0xff & encodedhash[i]);
                if(hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** Classify a Statement AST node into one of the 11 core node types. */
    private static String classifyNodeType(Statement stmt) {
        if (stmt instanceof IfStmt || stmt instanceof SwitchStmt) return "if";
        if (stmt instanceof ForStmt || stmt instanceof ForEachStmt) return "for";
        if (stmt instanceof WhileStmt || stmt instanceof DoStmt) return "while";
        if (stmt instanceof ReturnStmt) return "return";
        if (stmt instanceof ExplicitConstructorInvocationStmt) return "call";
        if (stmt instanceof ExpressionStmt) {
            Expression expr = ((ExpressionStmt) stmt).getExpression();
            if (expr instanceof MethodCallExpr)              return "call";
            if (expr instanceof ObjectCreationExpr)           return "call";
            if (expr instanceof AssignExpr)                   return "assign";
            if (expr instanceof VariableDeclarationExpr)      return "assign";
        }
        return "statement";  // BlockStmt, TryStmt, ThrowStmt, etc.
    }

    /** Extract the primary variable name from a statement, if applicable. */
    private static String extractVariable(Statement stmt) {
        if (!(stmt instanceof ExpressionStmt)) return null;
        Expression expr = ((ExpressionStmt) stmt).getExpression();
        if (expr instanceof VariableDeclarationExpr)
            return ((VariableDeclarationExpr) expr).getVariables().get(0).getNameAsString();
        if (expr instanceof AssignExpr)
            return ((AssignExpr) expr).getTarget().toString();
        return null;
    }

    private boolean parse(File file, Set<CompilationUnit> units, List<Problem> problems) {
        try {
            units.add(StaticJavaParser.parse(file));
        } catch (FileNotFoundException e) {
            problems.add(new Problem(e.getLocalizedMessage(), null, e));
        } catch (Exception e) {
            // ParseProblemException (RuntimeException) for unsupported syntax (e.g. Java 14+ switch arrows)
            System.err.println("WARN: Skipping unparseable file " + file.getName() + ": " + e.getMessage().split("\n")[0]);
        }
        return scFile != null && Objects.equals(file.getAbsoluteFile(), scFile.getAbsoluteFile());
    }

    protected Stream<File> findAllJavaFiles(Collection<File> files) {
        Stream.Builder<File> builder = Stream.builder();
        for (File file : files)
            if (file.isDirectory())
                findAllJavaFiles(file, builder);
            else
                builder.accept(file);
        return builder.build();
    }

    protected Stream<File> findAllJavaFiles(File directory) {
        Stream.Builder<File> builder = Stream.builder();
        findAllJavaFiles(directory, builder);
        return builder.build();
    }

    protected void findAllJavaFiles(File directory, Stream.Builder<File> builder) {
        File[] files = directory.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            if (f.isDirectory())
                findAllJavaFiles(f, builder);
            else if (f.getName().endsWith(".java"))
                builder.accept(f);
        }
    }

    protected String getDisclaimer(CompilationUnit.Storage s) {
        return String.format("\n\tThis file was automatically generated as part of a slice with criterion" +
                        "\n\tfile: %s, line: %d, variable: %s\n\tOriginal file: %s\n",
                scFile, scLine, scVar, s.getPath());
    }

    protected void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setWidth(120);
        formatter.printHelp("java -jar sdg-cli.jar", HELP_HEADER, OPTIONS, "", true);
        System.exit(0);
    }

    public static void main(String... args) {
        try {
            new Slicer(args).slice();
        } catch (ParseException e) {
            System.err.println("Error parsing the arguments!\n" + e.getMessage());
        }
    }
}
