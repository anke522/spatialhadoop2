/**
 * 
 */
package edu.umn.cs.spatialHadoop.nasa;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.util.GenericOptionsParser;

import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.nasa.AggregateQuadTree.Node;
import edu.umn.cs.spatialHadoop.temporal.TemporalIndex;
import edu.umn.cs.spatialHadoop.temporal.TemporalIndex.TemporalPartition;

/**
 * @author Ahmed Eldawy
 *
 */
public class SpatioTemporalSelectionQuery {
  
  public static class TimeRange {
    /**Date format of time range*/
    static final SimpleDateFormat DateFormat = new SimpleDateFormat("yyyy.MM.dd");
    
    /**Regular expression for a time range*/
    static final Pattern TimeRange = Pattern.compile("^(\\d{4}\\.\\d{2}\\.\\d{2})\\.\\.(\\d{4}\\.\\d{2}\\.\\d{2})$");

    /**Start time (inclusive)*/
    public long start;
    /**End time (exclusive)*/
    public long end;
    
    public TimeRange(String str) throws ParseException {
      Matcher matcher = TimeRange.matcher(str);
      if (!matcher.matches())
        throw new RuntimeException("Illegal time range '"+str+"'");
      start = DateFormat.parse(matcher.group(1)).getTime();
      end = DateFormat.parse(matcher.group(2)).getTime();
    }
    
    public TimeRange(long start, long end) {
      this.start = start;
      this.end = end;
    }
  }
  
  /**A regular expression to catch the tile identifier of a MODIS grid cell*/
  private static final Pattern MODISTileID = Pattern.compile("^.*h(\\d\\d)v(\\d\\d).*$"); 


  /**
   * Performs a spatio-temporal aggregate query on an indexed directory
   * @param inFile
   * @param params
   * @throws ParseException 
   * @throws IOException 
   */
  public static void aggregateQuery(Path inFile, OperationsParams params) throws ParseException, IOException {
    // 1- Run a temporal filter step to find all matching temporal partitions
    Vector<Path> matchingPartitions = new Vector<Path>();
    // List of time ranges to check. Initially it contains one range as
    // specified by the user. Eventually, it can be split into at most two
    // partitions if partially matched by a partition.
    Vector<TimeRange> temporalRanges = new Vector<TimeRange>();
    temporalRanges.add(new TimeRange(params.get("time")));
    Path[] temporalIndexes = new Path[] {
      new Path(inFile, "yearly"),
      new Path(inFile, "monthly"),
      new Path(inFile, "daily")
    };
    int index = 0;
    FileSystem fs = inFile.getFileSystem(params);
    while (index < temporalIndexes.length && !temporalRanges.isEmpty()) {
      Path indexDir = temporalIndexes[index];
      TemporalIndex temporalIndex = new TemporalIndex(fs, indexDir);
      for (int iRange = 0; iRange < temporalRanges.size(); iRange++) {
        TimeRange range = temporalRanges.get(iRange);
        TemporalPartition[] matches = temporalIndex.selectContained(range.start, range.end);
        if (matches != null) {
          for (TemporalPartition match : matches)
            matchingPartitions.add(new Path(indexDir, match.dirName));
          // Update range to remove matching part
          TemporalPartition firstMatch = matches[0];
          TemporalPartition lastMatch = matches[matches.length - 1];
          if (range.start < firstMatch.start && range.end > lastMatch.end) {
            // Need to split the range into two
            temporalRanges.setElementAt(
                new TimeRange(range.start, firstMatch.start), iRange);
            temporalRanges.insertElementAt(
                new TimeRange(lastMatch.end, range.end), iRange);
          } else if (range.start < firstMatch.start) {
            // Update range in-place
            range.end = firstMatch.start;
          } else if (range.end > lastMatch.end) {
            // Update range in-place
            range.start = lastMatch.end;
          } else {
            // Current range was completely covered. Remove it
            temporalRanges.remove(iRange);
          }
        }
      }
      index++;
    }
    
    // 2- Run a spatial aggregate query in each matched partition
    Rectangle spatialRange = params.getShape("rect", new Rectangle()).getMBR();
    // Convert spatialRange from lat/lng space to Sinusoidal space
    double cosPhiRad = Math.cos(spatialRange.y1 * Math.PI / 180);
    double southWest = spatialRange.x1 * cosPhiRad;
    double southEast = spatialRange.x2 * cosPhiRad;
    cosPhiRad = Math.cos(spatialRange.y2 * Math.PI / 180);
    double northWest = spatialRange.x1 * cosPhiRad;
    double northEast = spatialRange.x2 * cosPhiRad;
    spatialRange.x1 = Math.min(northWest, southWest);
    spatialRange.x2 = Math.max(northEast, southEast);
    // Convert to the h v space used by MODIS
    spatialRange.x1 = (spatialRange.x1 + 180.0) / 10.0;
    spatialRange.x2 = (spatialRange.x2 + 180.0) / 10.0;
    spatialRange.y2 = (90.0 - spatialRange.y2) / 10.0;
    spatialRange.y1 = (90.0 - spatialRange.y1) / 10.0;
    // Vertically flip because the Sinusoidal space increases to the south
    double tmp = spatialRange.y2;
    spatialRange.y2 = spatialRange.y1;
    spatialRange.y1 = tmp;
    // Find the range of cells in MODIS Sinusoidal grid overlapping the range
    final int h1 = (int) Math.floor(spatialRange.x1);
    final int h2 = (int) Math.ceil(spatialRange.x2);
    final int v1 = (int) Math.floor(spatialRange.y1);
    final int v2 = (int) Math.ceil(spatialRange.y2);
    PathFilter rangeFilter = new PathFilter() {
      @Override
      public boolean accept(Path p) {
        Matcher matcher = MODISTileID.matcher(p.getName());
        if (!matcher.matches())
          return false;
        int h = Integer.parseInt(matcher.group(1));
        int v = Integer.parseInt(matcher.group(2));
        return h >= h1 && h < h2 && v >= v1 && v < v2;
      }
    };
    
    AggregateQuadTree.Node finalResult = new AggregateQuadTree.Node();
    
    for (Path matchingPartition : matchingPartitions) {
      // Select all matching files
      FileStatus[] matchingFiles = fs.listStatus(matchingPartition, rangeFilter);
      for (FileStatus matchingFile : matchingFiles) {
        Matcher matcher = MODISTileID.matcher(matchingFile.getPath().getName());
        matcher.matches(); // It has to match
        int h = Integer.parseInt(matcher.group(1));
        int v = Integer.parseInt(matcher.group(2));
        // Clip the query region and normalize in this tile
        Rectangle translated = spatialRange.translate(-h, -v);
        int x1 = (int) (Math.max(translated.x1, 0) * 1200);
        int y1 = (int) (Math.max(translated.y1, 0) * 1200);
        int x2 = (int) (Math.min(translated.x2, 1.0) * 1200);
        int y2 = (int) (Math.min(translated.y2, 1.0) * 1200);
        AggregateQuadTree.Node fileResult = AggregateQuadTree.aggregateQuery(fs, matchingFile.getPath(),
            new java.awt.Rectangle(x1, y1, (x2 - x1), (y2 - y1)));
        finalResult.accumulate(fileResult);
      }
    }
    
    System.out.println("Final Result: "+finalResult);
  }
  
  /**
   * Prints the usage of this operation.
   */
  public static void printUsage() {
    System.out.println("Runs a spatio-temporal aggregate query on indexed MODIS data");
    System.out.println("Parameters: (* marks required parameters)");
    System.out.println("<input file> - (*) Path to input file");
    System.out.println("rect:<x1,y1,x2,y2> - (*) Spatial query range");
    System.out.println("time:<date1..date2> - (*) Temporal query range. "
        + "Format of each date is yyyy.mm.dd");
    GenericOptionsParser.printGenericCommandUsage(System.out);
  }
  
  /**
   * @param args
   * @throws IOException 
   * @throws ParseException 
   */
  public static void main(String[] args) throws IOException, ParseException {
    final OperationsParams params =
        new OperationsParams(new GenericOptionsParser(args), false);
    // Check input
    if (!params.checkInput()) {
      printUsage();
      System.exit(1);
    }
    
    if (params.get("rect") == null) {
      System.err.println("Spatial range missing");
      printUsage();
      System.exit(1);
    }

    if (params.get("time") == null) {
      System.err.println("Temporal range missing");
      printUsage();
      System.exit(1);
    }
    
    if (!TimeRange.TimeRange.matcher(params.get("time")).matches()) {
      System.err.print("Illegal time range format '"+params.get("time")+"'");
      printUsage();
      System.exit(1);
    }
    
    long t1 = System.currentTimeMillis();
    aggregateQuery(params.getInputPath(), params);
    long t2 = System.currentTimeMillis();
    System.out.println("Aggregate query finished in "+(t2-t1)+" millis");
  }

}
