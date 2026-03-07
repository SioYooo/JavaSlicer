# Java SDG Slicer

A program slicer for Java, based on the system dependence graph (SDG). *Program slicing* is a software analysis technique to extract the subset of statements that are relevant to the value of a variable in a specific statement (the *slicing criterion*). The subset of statements is called a *slice*, and it can be used for debugging, parallelization, clone detection, etc.

## Project Structure

```
JavaSlicer/
├── sdg-core/          # Core slicing library (SDG construction, slicing algorithms)
├── sdg-cli/           # Command-line interface for sdg-core
├── sdg-bench/         # Benchmarking module
├── javaparser-symbol-solver-core/  # Custom JavaParser symbol resolution
├── examples/          # Example Java programs for slicing
├── data_raw_java/     # Source projects for batch slicing
└── Dockerfile         # Docker build environment
```

### Modules

| Module | Description |
|--------|-------------|
| `sdg-core` | Library that builds the SDG from Java source code and computes slices. Uses JavaParser 3.23.x for AST handling. |
| `sdg-cli` | CLI client. Takes a Java program and slicing criterion as input, outputs the slice. |
| `javaparser-symbol-solver-core` | Customized JavaParser symbol solver for method/type resolution. |
| `sdg-bench` | Benchmark suite for evaluating slicer performance. |

### Graph Types

The slicer supports multiple graph types with increasing precision:

| Type | Description |
|------|-------------|
| `SDG` | Base System Dependence Graph |
| `ASDG` | Augmented SDG |
| `PSDG` | Procedural SDG |
| `ESSDG` | Exception-Sensitive SDG |
| `JSysDG` | Java System Dependence Graph (default, most precise) |

## Quick Start

### Prerequisites

- **JDK 11+** and **Maven**

Using conda:

```bash
conda create -n javaslicer python=3.10
conda activate javaslicer
conda install -c conda-forge openjdk=11 maven
```

### Build the Project

```bash
mvn package -Dmaven.test.skip
```

A fat jar containing all dependencies will be generated at:

```
sdg-cli/target/sdg-cli-1.3.0-jar-with-dependencies.jar
```

### Docker Build (Alternative)

```bash
docker build -t javaslicer .
docker run -it javaslicer
# Inside the container, the jar is at /SDG/javaSDG.jar
```

## Usage

### CLI Options

```
java -jar sdg-cli.jar [options]
```

| Option | Long | Argument | Description |
|--------|------|----------|-------------|
| `-c` | `--criterion` | `file#line[:var]` | Slicing criterion (file, line, optional variable) |
| `-f` | `--file` | `file.java` | Criterion file (alternative to `-c`) |
| `-l` | `--line` | `line-number` | Criterion line number (use with `-f`) |
| `-v` | `--var` | `variable-name` | Criterion variable name (optional, use with `-f` and `-l`) |
| `-i` | `--include` | `dir[,dir,...]` | Additional source directories to include in analysis |
| `-o` | `--output` | `output-dir` | Output directory (default: `./slice/`) |
| `-t` | `--type` | `graph-type` | Graph type: SDG, ASDG, PSDG, ESSDG, JSysDG (default: JSysDG) |
| `-a` | `--all` | *(flag)* | Slice all variables in the project, export to JSON |
| `-p` | `--project` | `project-name` | Project name for EID generation (used with `-a`) |
| `-h` | `--help` | *(flag)* | Show help |

### Single Criterion Slicing

Given the following program saved as `Example.java`:

```java
public class Example {
    public static void main(String[] args) {
        int sum = 0;
        int prod = 0;
        int i;
        int n = 10;
        for (i = 0; i < 10; i++) {
            sum += 1;
            prod += n;
        }
        System.out.println(sum);
        System.out.println(prod);
    }
}
```

Slice with respect to variable `sum` in line 11:

```bash
java -jar sdg-cli.jar -c Example.java#11:sum
```

For multi-file projects, include the source directories:

```bash
java -jar sdg-cli.jar -c Example2.java#8:x -i ./examples/Example2
```

### Slice-All Mode (`-a`)

Slice **every** variable in every function of a project and export results to a single JSON file:

```bash
java -jar sdg-cli.jar -a -p my_project -i ./path/to/project -o ./output
```

This generates `./output/slicing_result.json` containing all functions, their variables, and backward slice labels for each statement.

**Output JSON schema:**

```json
[
  {
    "eid": "<sha256 hash of project|file|function|signature>",
    "project_name": "my_project",
    "function_name": "main",
    "class_name": "Example",
    "file_name": "Example.java",
    "language": "java",
    "function_code": "public static void main(String[] args) { ... }",
    "slices": [
      {
        "slice_criterion": { "variable": "sum", "line": 3 },
        "nodes": [
          { "line": 3, "code": "int sum = 0;", "y_fwd": 0, "y_bwd": 1, "id": "3:9" },
          { "line": 4, "code": "int prod = 0;", "y_fwd": 0, "y_bwd": 0, "id": "4:9" }
        ],
        "edges": []
      }
    ]
  }
]
```

### Third-Party Libraries

The slicer requires the input Java program to be compilable, so all libraries must be provided using the `-i` flag. For cases where source code is not available, include compiled libraries in the classpath:

```bash
java -cp your-libraries.jar -jar sdg-cli.jar -c Example.java#11:sum
```

This approach produces lower quality slices, as the contents of library calls are unknown.

## Batch Slicing for `data_raw_java`

For slicing all projects under `data_raw_java/` at scale, use the batch pipeline from [gloslicer](https://github.com/SioYooo/gloslicer).

### Step 1: Batch Run JavaSlicer

The script `gloslicer/utils/batch_slice.py` automates running JavaSlicer in `-a` mode across all projects in parallel:

```powershell
cd E:\code\gloslicer; python utils/batch_slice.py --data_raw E:\code\JavaSlicer\data_raw_java --output_dir E:\code\gloslicer\javaslicer_slice --jar_path E:\code\JavaSlicer\sdg-cli\target\sdg-cli-1.3.0-jar-with-dependencies.jar --workers 8
```

**What it does:**

1. Scans `data_raw_java/` for all project subdirectories
2. Filters projects by language (default: Java, `--language java`)
3. Runs `java -jar sdg-cli.jar -a -p <name> -i <dir> -o <out>` for each project in parallel
4. Splits projects into train/val/test (60/20/20) with `--seed 42`
5. Aggregates per-project results into `train.json`, `val.json`, `test.json`

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--data_raw` | `data_raw` | Root directory containing project subdirectories |
| `--output_dir` | `javaslicer_slice` | Output directory |
| `--jar_path` | *(required)* | Path to `sdg-cli-*-jar-with-dependencies.jar` |
| `--workers` | 64 | Number of parallel processes |
| `--seed` | 42 | Random seed for train/val/test split |
| `--min-files` | 1 | Minimum Java files to include a project |
| `--max-files` | None | Maximum Java files per project |

**Output structure:**

```
javaslicer_slice/
├── project_A/
│   └── slicing_result.json
├── project_B/
│   └── slicing_result.json
├── ...
├── train.json          # Aggregated train split
├── val.json            # Aggregated val split
└── test.json           # Aggregated test split
```

### Step 2: Bridge to TreeSitter Format

JavaSlicer provides **labels** (which lines are in the slice), but the gloslicer training pipeline also requires **structural information** (node types, CFG/DFG edges). The external slicer bridge (`gloslicer/slicer_third_party/`) fills this gap by aligning JavaSlicer labels with TreeSitter AST nodes.

**Single project (for testing):**

```powershell
cd E:\code\gloslicer; python slicer_third_party/external_slicer_bridge.py --slicer javaslicer --input javaslicer_slice/<project>/slicing_result.json --project-root E:\code\JavaSlicer\data_raw_java\<project> --output bridged_slices/<project>/slices.json --language java --validate
```

**Batch run (all projects in parallel):**

```powershell
cd E:\code\gloslicer; python slicer_third_party/run_external_bridge.py --slicer javaslicer --slicer-results-dir E:\code\gloslicer\javaslicer_slice --dataset-dir E:\code\JavaSlicer\data_raw_java --output-dir E:\code\gloslicer\bridged_slices --language java --workers 8 --validate
```

**What the bridge does:**

1. Parses JavaSlicer's `slicing_result.json` via `JavaSlicerAdapter`
2. Re-parses source code with TreeSitter to extract AST node types (`statement`, `function`, `if`, `return`, `variable`, `class`, `call`, `assign`, `while`, `for`, `expr`) and build CFG/DFG edges
3. Aligns JavaSlicer line numbers to TreeSitter nodes using a 4-phase algorithm:
   - **Phase 1 – Exact match:** line number matches directly
   - **Phase 2 – Fuzzy match:** within +/-2 lines, token similarity >= 0.7
   - **Phase 3 – Range containment:** line falls within a multi-line node's range
   - **Phase 4 – Content match:** global search by token overlap >= 0.8
4. Outputs dataloader-compatible JSON with node types, edges, and `y_bwd` labels

**Output structure:**

```
bridged_slices/
├── train/
│   └── <project>/slices.json
├── val/
│   └── <project>/slices.json
├── test/
│   └── <project>/slices.json
├── train_slices.json    # Merged train split
├── val_slices.json      # Merged val split
├── test_slices.json     # Merged test split
└── failures.json        # Failed projects (if any)
```

### End-to-End Pipeline

```
data_raw_java/             batch_slice.py              external_slicer_bridge
  ├── project_A/ ────────→ slicing_result.json ────────→ slices.json (dataloader-ready)
  ├── project_B/ ────────→ slicing_result.json ────────→ slices.json
  └── ...
                           (labels only:                (labels + node types +
                            y_bwd per line)              CFG/DFG edges)
```

The final output in `bridged_slices/` is directly consumable by gloslicer's training pipeline (`gloslicer/train.py`).

## Library Usage

To use `sdg-core` programmatically, see [`Slicer.java#slice()`](/sdg-cli/src/main/java/es/upv/mist/slicing/cli/Slicer.java) for a reference implementation:

1. Configure JavaParser to resolve calls in the JRE and user-defined libraries, and to ignore comments.
2. Parse the Java files to build a list of `CompilationUnit`s.
3. Create the SDG based on that list.
4. Create a `SlicingCriterion` from the input arguments and obtain the slice.
5. Convert the slice to a list of `CompilationUnit` (each representing a file).
6. Dump the contents of each `CompilationUnit` to their corresponding file.

The graph can be outputted in `dot` or PDF format via `SDGLog#generateImages()`.

## Missing Java Features

- Parallel features: threads, shared memory, synchronized methods, etc.

## Cite This Software

> Carlos Galindo, Sergio Perez, Josep Silva. 2022. A program slicer for Java (tool paper). International Conference on Software Engineering and Formal Methods, 146-151.

<details>
<summary>BibTeX entry</summary>

```bibtex
@InProceedings{javaslicer,
  author    = {Galindo, Carlos and Perez, Sergio and Silva, Josep},
  editor    = {Schlingloff, Bernd-Holger and Chai, Ming},
  title     = {A Program Slicer for Java (Tool Paper)},
  booktitle = {Software Engineering and Formal Methods},
  series    = {SEFM},
  year      = {2022},
  publisher = {Springer International Publishing},
  address   = {Cham},
  pages     = {146--151},
  isbn      = {978-3-031-17108-6},
  doi       = {10.1007/978-3-031-17108-6_9}
}
```

</details>

[doi]: https://doi.org/10.1007/978-3-031-17108-6_9
