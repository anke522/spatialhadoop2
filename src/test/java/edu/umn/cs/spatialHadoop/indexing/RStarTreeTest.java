package edu.umn.cs.spatialHadoop.indexing;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

/**
 * Unit test for the RTreeGuttman class
 */
public class RStarTreeTest extends TestCase {

  /**
   * Create the test case
   *
   * @param testName
   *          name of the test case
   */
  public RStarTreeTest(String testName) {
    super(testName);
  }

  /**
   * @return the suite of tests being tested
   */
  public static Test suite() {
    return new TestSuite(RStarTreeTest.class);
  }

  public void testBuild() {
    try {
      String fileName = "src/test/resources/test2.points";
      double[][] points = readFile(fileName);
      RTreeGuttman rtree = new RStarTree(points[0], points[1], 4, 8);
      assertEquals(rtree.numOfDataEntries(), 22);
      int maxNumOfNodes = 6;
      int minNumOfNodes = 4;
      assertTrue(String.format("Too few nodes %d<%d",rtree.numOfNodes(), minNumOfNodes),
          rtree.numOfNodes() >= minNumOfNodes);
      assertTrue(String.format("Too many nodes %d>%d", rtree.numOfNodes(), maxNumOfNodes),
          rtree.numOfNodes() <= maxNumOfNodes);
      assertEquals(2, rtree.getHeight());
    } catch (FileNotFoundException e) {
      fail("Error opening test file");
    } catch (IOException e) {
      fail("Error working with the test file");
    }
  }

  /**
   * Read a CSV file that contains one point per line in the format "x,y".
   * The points are returned as a 2D array where the first index indicates the
   * coordinate (0 for x and 1 for y) and the second index indicates the point
   * number.
   * @param fileName
   * @return
   * @throws IOException
   */
  private double[][] readFile(String fileName) throws IOException {
    FileReader testPointsIn = new FileReader(fileName);
    char[] buffer = new char[(int) new File(fileName).length()];
    testPointsIn.read(buffer);
    testPointsIn.close();

    String[] lines = new String(buffer).split("\\s");
    double[] xs = new double[lines.length];
    double[] ys = new double[lines.length];
    for (int iLine = 0; iLine < lines.length; iLine++) {
      String[] parts = lines[iLine].split(",");
      xs[iLine] = Double.parseDouble(parts[0]);
      ys[iLine] = Double.parseDouble(parts[1]);
    }
    return new double[][]{xs, ys};
  }

}
