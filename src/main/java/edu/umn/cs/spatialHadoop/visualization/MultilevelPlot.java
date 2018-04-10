/***********************************************************************
 * Copyright (c) 2015 by Regents of the University of Minnesota.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0 which
 * accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 *
 *************************************************************************/
package edu.umn.cs.spatialHadoop.visualization;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Vector;

import javax.imageio.ImageIO;

import edu.umn.cs.spatialHadoop.util.FSUtil;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.LocalJobRunner;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.util.LineReader;

import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.GridInfo;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.Shape;
import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.mapreduce.SpatialInputFormat3;
import edu.umn.cs.spatialHadoop.mapreduce.SpatialRecordReader3;
import edu.umn.cs.spatialHadoop.nasa.HDFRecordReader;
import edu.umn.cs.spatialHadoop.operations.FileMBR;
import edu.umn.cs.spatialHadoop.util.Parallel;
import edu.umn.cs.spatialHadoop.util.Parallel.RunnableRange;

/**
 * Generates a multilevel image
 *
 * @author Ahmed Eldawy
 */
public class MultilevelPlot {
  private static final Log LOG = LogFactory.getLog(MultilevelPlot.class);
  /** Configuration entry for input MBR */
  private static final String InputMBR = "mbr";

  /** Maximum height for a pyramid to be generated by one machine */
  public static final String MaxLevelsPerReducer = "MultilevelPlot.MaxLevelsPerMachine";

  /** The maximum level on which flat partitioning can be used */
  public static final String FlatPartitioningLevelThreshold = "MultilevelPlot.FlatPartitioningLevelThreshold";

  public static class FlatPartitionMap extends Mapper<Rectangle, Iterable<? extends Shape>, LongWritable, Canvas> {
    /** The subpyramid that defines the tiles of interest*/
    private SubPyramid subPyramid;

    /** The plotter associated with this job */
    private Plotter plotter;

    /** Fixed width for one tile */
    private int tileWidth;

    /** Fixed height for one tile */
    private int tileHeight;

    /** Buffer size that should be taken in the maximum level */
    private double bufferSizeXMaxLevel;

    private double bufferSizeYMaxLevel;

    /** Whether the configured plotter supports smooth or not */
    private boolean smooth;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
      super.setup(context);
      Configuration conf = context.getConfiguration();
      String[] strLevels = conf.get("levels", "7").split("\\.\\.");
      int minLevel, maxLevel;
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }
      Rectangle inputMBR = (Rectangle) OperationsParams.getShape(conf, InputMBR);
      subPyramid = new SubPyramid(inputMBR, minLevel, maxLevel, 0, 0, 1 << maxLevel, 1 << maxLevel);
      this.tileWidth = conf.getInt("tilewidth", 256);
      this.tileHeight = conf.getInt("tileheight", 256);
      this.plotter = Plotter.getPlotter(conf);
      this.smooth = plotter.isSmooth();
    }

    @Override
    protected void map(Rectangle partition, Iterable<? extends Shape> shapes, Context context)
        throws IOException, InterruptedException {
      if (smooth)
        shapes = plotter.smooth(shapes);
      Map<Long, Canvas> canvasLayers = new HashMap<Long, Canvas>();
      createTiles(shapes, subPyramid, tileWidth, tileHeight, plotter, canvasLayers);
      // Write all created layers to the output
      LongWritable outKey = new LongWritable();
      for (Map.Entry<Long, Canvas> entry : canvasLayers.entrySet()) {
        outKey.set(entry.getKey());
        context.write(outKey, entry.getValue());
      }
    }
  }

  public static class FlatPartitionReduce extends Reducer<LongWritable, Canvas, LongWritable, Canvas> {
    /** Minimum and maximum levels of the pyramid to plot (inclusive and zero-based) */
    private int minLevel, maxLevel;

    /** The grid at the bottom level (i.e., maxLevel) */
    private GridInfo bottomGrid;

    /** The MBR of the input area to draw */
    private Rectangle inputMBR;

    /** The plotter associated with this job */
    private Plotter plotter;

    /** Fixed width for one tile */
    private int tileWidth;

    /** Fixed height for one tile */
    private int tileHeight;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
      super.setup(context);
      Configuration conf = context.getConfiguration();
      String[] strLevels = conf.get("levels", "7").split("\\.\\.");
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }
      this.inputMBR = (Rectangle) OperationsParams.getShape(conf, InputMBR);
      this.bottomGrid = new GridInfo(inputMBR.x1, inputMBR.y1, inputMBR.x2, inputMBR.y2);
      this.bottomGrid.rows = bottomGrid.columns = 1 << maxLevel;
      this.tileWidth = conf.getInt("tilewidth", 256);
      this.tileHeight = conf.getInt("tileheight", 256);
      this.plotter = Plotter.getPlotter(conf);
    }

    @Override
    protected void reduce(LongWritable tileID, Iterable<Canvas> interLayers, Context context)
        throws IOException, InterruptedException {
      Rectangle tileMBR = TileIndex.getMBR(inputMBR, tileID.get());
      Canvas finalLayer = plotter.createCanvas(tileWidth, tileHeight, tileMBR);
      for (Canvas interLayer : interLayers) {
        plotter.merge(finalLayer, interLayer);
        context.progress();
      }

      context.write(tileID, finalLayer);
    }
  }

  public static class PyramidPartitionMap extends Mapper<Rectangle, Iterable<? extends Shape>, LongWritable, Shape> {

    /** The sub-pyramid that represents the tiles of interest */
    private SubPyramid subPyramid;

    /** Maximum number of levels to assign to one reducer (parameter k in the paper)*/
    private int maxLevelsPerReducer;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
      super.setup(context);
      Configuration conf = context.getConfiguration();
      String[] strLevels = conf.get("levels", "7").split("\\.\\.");
      int minLevel, maxLevel;
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }
      this.maxLevelsPerReducer = conf.getInt(MaxLevelsPerReducer, 3);
      // Adjust the maximum level according to the maxLevelsPerReducer (k) parameter
      // such that we cover the range of levels of interest and the minimum z
      // to replicate to is the same as the minimum level of interest
      maxLevel -= (maxLevel - minLevel) % maxLevelsPerReducer;
      Rectangle inputMBR = (Rectangle) OperationsParams.getShape(conf, InputMBR);
      subPyramid = new SubPyramid(inputMBR, minLevel, maxLevel, 0, 0, 1 << maxLevel, 1 << maxLevel);
    }

    @Override
    protected void map(Rectangle partition, Iterable<? extends Shape> shapes, Context context)
        throws IOException, InterruptedException {
      java.awt.Rectangle overlaps = new java.awt.Rectangle();
      int i = 0;
      LongWritable outKey = new LongWritable();
      for (Shape shape : shapes) {
        Rectangle shapeMBR = shape.getMBR();
        if (shapeMBR == null)
          continue;
        subPyramid.getOverlappingTiles(shapeMBR, overlaps);
        // Iterate over levels from bottom up
        for (int z = subPyramid.maximumLevel; z >= subPyramid.minimumLevel;
             z -= maxLevelsPerReducer) {
          for (int x = overlaps.x; x < overlaps.x + overlaps.width; x++) {
            for (int y = overlaps.y; y < overlaps.y + overlaps.height; y++) {
              outKey.set(TileIndex.encode(z, x, y));
              context.write(outKey, shape);
            }
          }
          // Shrink overlapping cells to match the upper z
          int updatedX1 = overlaps.x >> maxLevelsPerReducer;
          int updatedY1 = overlaps.y >> maxLevelsPerReducer;
          int updatedX2 = (overlaps.x + overlaps.width - 1) >> maxLevelsPerReducer;
          int updatedY2 = (overlaps.y + overlaps.height - 1) >> maxLevelsPerReducer;
          overlaps.x = updatedX1;
          overlaps.y = updatedY1;
          overlaps.width = updatedX2 - updatedX1 + 1;
          overlaps.height = updatedY2 - updatedY1 + 1;
        }

        if (((++i) & 0xff) == 0)
          context.progress();
      }
    }
  }

  public static class PyramidPartitionReduce extends Reducer<LongWritable, Shape, LongWritable, Canvas> {

    private int minLevel, maxLevel;
    private Rectangle inputMBR;
    /** The user-configured plotter */
    private Plotter plotter;
    /** Maximum levels to generate per reducer */
    private int maxLevelsPerReducer;
    /** Size of each tile in pixels */
    private int tileWidth, tileHeight;
    /** Whether the configured plotter defines a smooth function or not */
    private boolean smooth;

    /** A sub-pyramid object to reuse in reducers*/
    private SubPyramid subPyramid = new SubPyramid();

    /**A temporary tile index to decode the tileID*/
    private TileIndex tempTileIndex;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
      super.setup(context);
      Configuration conf = context.getConfiguration();
      String[] strLevels = conf.get("levels", "7").split("\\.\\.");
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }
      this.maxLevelsPerReducer = conf.getInt(MaxLevelsPerReducer, 3);
      this.inputMBR = (Rectangle) OperationsParams.getShape(conf, InputMBR);
      this.plotter = Plotter.getPlotter(conf);
      this.smooth = plotter.isSmooth();
      this.tileWidth = conf.getInt("tilewidth", 256);
      this.tileHeight = conf.getInt("tileheight", 256);
    }

    @Override
    protected void reduce(LongWritable tileID, Iterable<Shape> shapes, Context context)
        throws IOException, InterruptedException {
      tempTileIndex = TileIndex.decode(tileID.get(), tempTileIndex);
      // Create the subpyramid associated with the given tileID
      int tileMaxLevel = Math.min(this.maxLevel, tempTileIndex.z + maxLevelsPerReducer - 1);
      int numLevelsInReducer = tileMaxLevel - tempTileIndex.z;
      int c1 = tempTileIndex.x << numLevelsInReducer;
      int r1 = tempTileIndex.y << numLevelsInReducer;
      int c2 = c1 + 1 << numLevelsInReducer;
      int r2 = r1 + 1 << numLevelsInReducer;
      subPyramid.set(inputMBR, tempTileIndex.z, tileMaxLevel, c1, r1, c2, r2);

      Map<Long, Canvas> canvasLayers = new HashMap<Long, Canvas>();

      context.setStatus("Plotting");
      if (smooth) {
        shapes = plotter.smooth(shapes);
        context.progress();
      }
      int i = 0;

      createTiles(shapes, subPyramid, tileWidth, tileHeight, plotter, canvasLayers);

      context.setStatus("Writing " + canvasLayers.size() + " tiles");
      // Write all created layers to the output as images
      LongWritable outKey = new LongWritable();
      for (Map.Entry<Long, Canvas> entry : canvasLayers.entrySet()) {
        outKey.set(entry.getKey());
        context.write(outKey, entry.getValue());
      }
    }
  }

  private static Job plotMapReduce(Path[] inFiles, Path outFile, Class<? extends Plotter> plotterClass,
                                   OperationsParams params) throws IOException, InterruptedException, ClassNotFoundException {
    Plotter plotter;
    try {
      plotter = plotterClass.newInstance();
    } catch (InstantiationException e) {
      throw new RuntimeException("Error creating rastierizer", e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Error creating rastierizer", e);
    }

    Job job = new Job(params, "MultilevelPlot");
    job.setJarByClass(SingleLevelPlot.class);
    // Set plotter
    Configuration conf = job.getConfiguration();
    Plotter.setPlotter(conf, plotterClass);
    // Set input file MBR
    Rectangle inputMBR = (Rectangle) params.getShape("mbr");
    if (inputMBR == null)
      inputMBR = FileMBR.fileMBR(inFiles, params);

    // Adjust width and height if aspect ratio is to be kept
    if (params.getBoolean("keepratio", true)) {
      // Expand input file to a rectangle for compatibility with the pyramid
      // structure
      if (inputMBR.getWidth() > inputMBR.getHeight()) {
        inputMBR.y1 -= (inputMBR.getWidth() - inputMBR.getHeight()) / 2;
        inputMBR.y2 = inputMBR.y1 + inputMBR.getWidth();
      } else {
        inputMBR.x1 -= (inputMBR.getHeight() - inputMBR.getWidth()) / 2;
        inputMBR.x2 = inputMBR.x1 + inputMBR.getHeight();
      }
    }
    OperationsParams.setShape(conf, InputMBR, inputMBR);

    // Set input and output
    job.setInputFormatClass(SpatialInputFormat3.class);
    SpatialInputFormat3.setInputPaths(job, inFiles);
    if (conf.getBoolean("output", true)) {
      job.setOutputFormatClass(PyramidOutputFormat3.class);
      PyramidOutputFormat3.setOutputPath(job, outFile);
    } else {
      job.setOutputFormatClass(NullOutputFormat.class);
    }

    // Set mapper, reducer and committer
    String partitionTechnique = params.get("partition", "flat");
    if (partitionTechnique.equalsIgnoreCase("flat")) {
      job.setJobName("MultilevelFlatPlot");
      // Use flat partitioning
      job.setMapperClass(FlatPartitionMap.class);
      job.setMapOutputKeyClass(LongWritable.class);
      job.setMapOutputValueClass(plotter.getCanvasClass());
      job.setReducerClass(FlatPartitionReduce.class);
    } else if (partitionTechnique.equalsIgnoreCase("pyramid")) {
      // Use pyramid partitioning
      job.setJobName("MultilevelPyramidPlot");
      Shape shape = params.getShape("shape");
      job.setMapperClass(PyramidPartitionMap.class);
      job.setMapOutputKeyClass(LongWritable.class);
      job.setMapOutputValueClass(shape.getClass());
      job.setReducerClass(PyramidPartitionReduce.class);
    } else {
      throw new RuntimeException("Unknown partitioning technique '" + partitionTechnique + "'");
    }
    // Set number of reducers
    job.setNumReduceTasks(Math.max(1, new JobClient(new JobConf()).getClusterStatus().getMaxReduceTasks() * 7 / 8));
    // Use multithreading in case the job is running locally
    conf.setInt(LocalJobRunner.LOCAL_MAX_MAPS, Runtime.getRuntime().availableProcessors());

    // Start the job
    if (params.getBoolean("background", false)) {
      job.submit();
    } else {
      job.waitForCompletion(false);
    }
    return job;
  }

  private static void plotLocal(Path[] inFiles, final Path outPath, final Class<? extends Plotter> plotterClass,
                                final OperationsParams params) throws IOException, InterruptedException, ClassNotFoundException {
    final boolean vflip = params.getBoolean("vflip", true);

    OperationsParams mbrParams = new OperationsParams(params);
    mbrParams.setBoolean("background", false);
    final Rectangle inputMBR = params.get("mbr") != null ? params.getShape("mbr").getMBR()
        : FileMBR.fileMBR(inFiles, mbrParams);
    OperationsParams.setShape(params, InputMBR, inputMBR);

    // Retrieve desired output image size and keep aspect ratio if needed
    int tileWidth = params.getInt("tilewidth", 256);
    int tileHeight = params.getInt("tileheight", 256);
    // Adjust width and height if aspect ratio is to be kept
    if (params.getBoolean("keepratio", true)) {
      // Expand input file to a rectangle for compatibility with the pyramid
      // structure
      if (inputMBR.getWidth() > inputMBR.getHeight()) {
        inputMBR.y1 -= (inputMBR.getWidth() - inputMBR.getHeight()) / 2;
        inputMBR.y2 = inputMBR.y1 + inputMBR.getWidth();
      } else {
        inputMBR.x1 -= (inputMBR.getHeight() - inputMBR.getWidth()) / 2;
        inputMBR.x2 = inputMBR.x1 + inputMBR.getHeight();
      }
    }

    String outFName = outPath.getName();
    int extensionStart = outFName.lastIndexOf('.');
    final String extension = extensionStart == -1 ? ".png" : outFName.substring(extensionStart);

    // Start reading input file
    Vector<InputSplit> splits = new Vector<InputSplit>();
    final SpatialInputFormat3<Rectangle, Shape> inputFormat = new SpatialInputFormat3<Rectangle, Shape>();
    for (Path inFile : inFiles) {
      FileSystem inFs = inFile.getFileSystem(params);
      if (!OperationsParams.isWildcard(inFile) && inFs.exists(inFile) && !inFs.isDirectory(inFile)) {
        if (SpatialSite.NonHiddenFileFilter.accept(inFile)) {
          // Use the normal input format splitter to add this non-hidden file
          Job job = Job.getInstance(params);
          SpatialInputFormat3.addInputPath(job, inFile);
          splits.addAll(inputFormat.getSplits(job));
        } else {
          // A hidden file, add it immediately as one split
          // This is useful if the input is a hidden file which is automatically
          // skipped by FileInputFormat. We need to plot a hidden file for the case
          // of plotting partition boundaries of a spatial index
          splits.add(new FileSplit(inFile, 0, inFs.getFileStatus(inFile).getLen(), new String[0]));
        }
      } else {
        Job job = Job.getInstance(params);
        SpatialInputFormat3.addInputPath(job, inFile);
        splits.addAll(inputFormat.getSplits(job));
      }
    }

    try {
      Plotter plotter = plotterClass.newInstance();
      plotter.configure(params);

      String[] strLevels = params.get("levels", "7").split("\\.\\.");
      int minLevel, maxLevel;
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]) - 1;
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }

      // Create the sub pyramid that represents all tiles of interest
      // Since we generate all tiles in the given range of levels,
      // we set the range of tiles to (0, 0, 2^maxLevel, 2^maxLevel)
      SubPyramid subPyramid = new SubPyramid(inputMBR, minLevel, maxLevel,
          0, 0, 1 << maxLevel, 1 << maxLevel);

      // Prepare the map that will eventually contain all the tiles
      Map<Long, Canvas> tiles = new HashMap<Long, Canvas>();

      for (InputSplit split : splits) {
        FileSplit fsplit = (FileSplit) split;
        RecordReader<Rectangle, Iterable<Shape>> reader = inputFormat.createRecordReader(fsplit, null);
        if (reader instanceof SpatialRecordReader3) {
          ((SpatialRecordReader3) reader).initialize(fsplit, params);
        } else if (reader instanceof HDFRecordReader) {
          ((HDFRecordReader) reader).initialize(fsplit, params);
        } else {
          throw new RuntimeException("Unknown record reader");
        }

        while (reader.nextKeyValue()) {
          Rectangle partition = reader.getCurrentKey();
          if (!partition.isValid())
            partition.set(inputMBR);

          Iterable<Shape> shapes = reader.getCurrentValue();

          createTiles(shapes, subPyramid, tileWidth, tileHeight, plotter, tiles);
        }
        reader.close();
      }

      // Done with all splits. Write output to disk
      LOG.info("Done with plotting. Now writing the output");
      final FileSystem outFS = outPath.getFileSystem(params);

      LOG.info("Writing default empty image");
      // Write a default empty image to be displayed for non-generated tiles
      BufferedImage emptyImg = new BufferedImage(tileWidth, tileHeight, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = new SimpleGraphics(emptyImg);
      g.setBackground(new Color(0, 0, 0, 0));
      g.clearRect(0, 0, tileWidth, tileHeight);
      g.dispose();

      // Write HTML file to browse the mutlielvel image
      OutputStream out = outFS.create(new Path(outPath, "default.png"));
      ImageIO.write(emptyImg, "png", out);
      out.close();

      // Add an HTML file that visualizes the result using Google Maps
      LOG.info("Writing the HTML viewer file");
      LineReader templateFileReader = new LineReader(MultilevelPlot.class.getResourceAsStream("/zoom_view.html"));
      PrintStream htmlOut = new PrintStream(outFS.create(new Path(outPath, "index.html")));
      Text line = new Text();
      while (templateFileReader.readLine(line) > 0) {
        String lineStr = line.toString();
        lineStr = lineStr.replace("#{TILE_WIDTH}", Integer.toString(tileWidth));
        lineStr = lineStr.replace("#{TILE_HEIGHT}", Integer.toString(tileHeight));
        lineStr = lineStr.replace("#{MAX_ZOOM}", Integer.toString(maxLevel));
        lineStr = lineStr.replace("#{MIN_ZOOM}", Integer.toString(minLevel));
        lineStr = lineStr.replace("#{TILE_URL}",
            "'tile-' + zoom + '-' + coord.x + '-' + coord.y + '" + extension + "'");

        htmlOut.println(lineStr);
      }
      templateFileReader.close();
      htmlOut.close();

      // Write the tiles
      final Entry<Long, Canvas>[] entries = tiles.entrySet().toArray(new Map.Entry[tiles.size()]);
      // Clear the hash map to save memory as it is no longer needed
      tiles.clear();
      int parallelism = params.getInt("parallel", Runtime.getRuntime().availableProcessors());
      Parallel.forEach(entries.length, new RunnableRange<Object>() {
        @Override
        public Object run(int i1, int i2) {
          TileIndex tempTileIndex = null;
          boolean output = params.getBoolean("output", true);
          try {
            Plotter plotter = plotterClass.newInstance();
            plotter.configure(params);
            for (int i = i1; i < i2; i++) {
              Map.Entry<Long, Canvas> entry = entries[i];
              Long tileID = entry.getKey();
              tempTileIndex = TileIndex.decode(tileID, tempTileIndex);
              if (vflip)
                tempTileIndex.y = ((1 << tempTileIndex.z) - 1) - tempTileIndex.y;
              Path imagePath = new Path(outPath, "tile-"+tempTileIndex.z +"-"+tempTileIndex.x+"-"+tempTileIndex.y+".png");

              // Write this tile to an image
              DataOutputStream outFile = output ? outFS.create(imagePath)
                  : new DataOutputStream(new NullOutputStream());
              plotter.writeImage(entry.getValue(), outFile, vflip);
              outFile.close();

              // Remove entry to allows GC to collect it
              entries[i] = null;
            }
            return null;
          } catch (InstantiationException e) {
            e.printStackTrace();
          } catch (IllegalAccessException e) {
            e.printStackTrace();
          } catch (IOException e) {
            e.printStackTrace();
          }
          return null;
        }
      }, parallelism);
    } catch (InstantiationException e) {
      throw new RuntimeException("Error creating rastierizer", e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Error creating rastierizer", e);
    }
  }

  /**
   * Creates and returns all the tiles in the given sub pyramid that contains
   * the given set of shapes.
   * @param shapes The shapes to be plotted
   * @param subPyramid The subpyramid that defines the range of tiles being considered
   * @param tileWidth Width of each tile in pixels
   * @param tileHeight Height of each tile in pixels
   * @param plotter The plotter used to create canvases
   * @param tiles The set of tiles that have been created already. It could be
   *              empty which indicates no tiles created yet.
   */
  public static void createTiles(
      Iterable<? extends Shape> shapes, SubPyramid subPyramid,
      int tileWidth, int tileHeight,
      Plotter plotter, Map<Long, Canvas> tiles) {
    Rectangle inputMBR = subPyramid.getInputMBR();
    java.awt.Rectangle overlaps = new java.awt.Rectangle();
    for (Shape shape : shapes) {
      if (shape == null)
        continue;
      Rectangle mbr = shape.getMBR();
      if (mbr == null)
        continue;

      subPyramid.getOverlappingTiles(mbr, overlaps);
      for (int z = subPyramid.maximumLevel; z >= subPyramid.minimumLevel; z--) {
        for (int x = overlaps.x; x < overlaps.x + overlaps.width; x++) {
          for (int y = overlaps.y; y < overlaps.y + overlaps.height; y++) {
            long tileID = TileIndex.encode(z, x, y);
            // Plot the shape on the tile at (z,x,y)
            Canvas c = tiles.get(tileID);
            if (c == null) {
              // First time to encounter this tile, create the corresponding canvas
              Rectangle tileMBR = TileIndex.getMBR(inputMBR, z, x, y);
              c = plotter.createCanvas(tileWidth, tileHeight, tileMBR);
              tiles.put(tileID, c);
            }
            plotter.plot(c, shape);
          }
        }
        // Update overlappingCells for the higher z
        int updatedX1 = overlaps.x / 2;
        int updatedY1 = overlaps.y / 2;
        int updatedX2 = (overlaps.x + overlaps.width - 1) / 2;
        int updatedY2 = (overlaps.y + overlaps.height - 1) / 2;
        overlaps.x = updatedX1;
        overlaps.y = updatedY1;
        overlaps.width = updatedX2 - updatedX1 + 1;
        overlaps.height = updatedY2 - updatedY1 + 1;

      }
    }
  }

  public static Job plot(Path[] inPaths, Path outPath, Class<? extends Plotter> plotterClass, OperationsParams params)
      throws IOException, InterruptedException, ClassNotFoundException {
    if (params.getBoolean("showmem", false)) {
      // Run a thread that keeps track of used memory
      Thread memThread = new Thread(new Thread() {
        @Override
        public void run() {
          Runtime runtime = Runtime.getRuntime();
          while (true) {
            try {
              Thread.sleep(60000);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
            runtime.gc();
            LOG.info("Memory usage: "
                + ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024 * 1024)) + "GB.");
          }
        }
      });
      memThread.setDaemon(true);
      memThread.start();
    }

    // Decide how to run it based on range of levels to generate
    String[] strLevels = params.get("levels", "7").split("\\.\\.");
    int minLevel, maxLevel;
    if (strLevels.length == 1) {
      minLevel = 0;
      maxLevel = Integer.parseInt(strLevels[0]) - 1;
    } else {
      minLevel = Integer.parseInt(strLevels[0]);
      maxLevel = Integer.parseInt(strLevels[1]);
    }
    // Create an output directory that will hold the output of the two jobs
    FileSystem outFS = outPath.getFileSystem(params);
    outFS.mkdirs(outPath);

    Job runningJob = null;
    if (OperationsParams.isLocal(params, inPaths)) {
      // Plot local
      plotLocal(inPaths, outPath, plotterClass, params);
    } else {
      int maxLevelWithFlatPartitioning = params.getInt(FlatPartitioningLevelThreshold, 4);

      if (minLevel <= maxLevelWithFlatPartitioning) {
        OperationsParams flatPartitioning = new OperationsParams(params);
        flatPartitioning.set("levels", minLevel + ".." + Math.min(maxLevelWithFlatPartitioning, maxLevel));
        flatPartitioning.set("partition", "flat");
        LOG.info("Using flat partitioning in levels " + flatPartitioning.get("levels"));
        runningJob = plotMapReduce(inPaths, new Path(outPath, "flat"), plotterClass, flatPartitioning);
      }
      if (maxLevel > maxLevelWithFlatPartitioning) {
        OperationsParams pyramidPartitioning = new OperationsParams(params);
        pyramidPartitioning.set("levels",
            Math.max(minLevel, maxLevelWithFlatPartitioning + 1) + ".." + maxLevel);
        pyramidPartitioning.set("partition", "pyramid");
        LOG.info("Using pyramid partitioning in levels " + pyramidPartitioning.get("levels"));
        runningJob = plotMapReduce(inPaths, new Path(outPath, "pyramid"), plotterClass, pyramidPartitioning);
      }
      // Move all output files to one directory
      FSUtil.flattenDirectory(outFS, outPath);

      // Write a new HTML file that displays both parts of the pyramid
      // Add an HTML file that visualizes the result using Google Maps
      LineReader templateFileReader = new LineReader(AdaptiveMultilevelPlot.class.getResourceAsStream("/zoom_view.html"));
      PrintStream htmlOut = new PrintStream(outFS.create(new Path(outPath, "index.html")));
      Text line = new Text();
      while (templateFileReader.readLine(line) > 0) {
        String lineStr = line.toString();
        lineStr = lineStr.replace("#{TILE_WIDTH}", Integer.toString(params.getInt("tilewidth", 256)));
        lineStr = lineStr.replace("#{TILE_HEIGHT}", Integer.toString(params.getInt("tileheight", 256)));
        lineStr = lineStr.replace("#{MAX_ZOOM}", Integer.toString(maxLevel));
        lineStr = lineStr.replace("#{MIN_ZOOM}", Integer.toString(minLevel));
        lineStr = lineStr.replace("#{TILE_URL}", "'tile-' + zoom + '-' + coord.x + '-' + coord.y + '.png'");

        htmlOut.println(lineStr);
      }
      templateFileReader.close();
      htmlOut.close();

    }

    return runningJob;
  }
}
