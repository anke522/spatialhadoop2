/***********************************************************************
* Copyright (c) 2015 by Regents of the University of Minnesota.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which 
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*
*************************************************************************/

import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.Shape;
import org.apache.hadoop.io.Text;

import java.awt.*;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * @author Eldawy
 *
 */
public class TweetTweet implements Shape {
  Tweet tweet1, tweet2;
  private final byte[] Comma = ",".getBytes();

  public TweetTweet() {
    tweet1 = new Tweet();
    tweet2 = new Tweet();
  }

  public TweetTweet(Tweet tweet1, Tweet tweet2) {
    this.tweet1 = new Tweet(tweet1);
    this.tweet2 = new Tweet(tweet2);
  }

  @Override
  public void fromText(Text text) {
    tweet1.fromText(text);
    // Skip the Tab
    text.set(text.getBytes(), 1, text.getLength() - 1);
    tweet2.fromText(text);
  }
  
  @Override
  public Text toText(Text text) {
    tweet1.toText(text);
    text.append(Comma, 0, Comma.length);
    tweet2.toText(text);
    return text;
  }
  
  @Override
  public void write(DataOutput out) throws IOException {
    tweet1.write(out);
    tweet2.write(out);
  }
  
  @Override
  public void readFields(DataInput in) throws IOException {
    tweet1.readFields(in);
    tweet2.readFields(in);
  }
  
  @Override
  public TweetTweet clone() {
    return new TweetTweet(tweet1, tweet2);
  }

  @Override
  public Rectangle getMBR() {
    return new Rectangle(tweet1.x, tweet1.y, tweet2.x, tweet2.y);
  }

  @Override
  public boolean isIntersected(Shape s) {
    return this.getMBR().isIntersected(s);
  }

  @Override
  public void draw(Graphics g, double xscale, double yscale) {
    int x1 = (int) Math.round(this.tweet1.x * xscale);
    int y1 = (int) Math.round(this.tweet1.y * yscale);
    int x2 = (int) Math.round(this.tweet2.x * xscale);
    int y2 = (int) Math.round(this.tweet2.y * yscale);
    g.drawLine(x1, y1, x2, y2);
  }

  @Override
  public void draw(Graphics g, Rectangle fileMBR, int imageWidth, int imageHeight, double scale) {
    throw new RuntimeException("Not supported");
  }

  @Override
  public double distanceTo(double x, double y) {
    return Math.min(tweet1.distanceTo(x, y), tweet2.distanceTo(x, y));
  }
}
