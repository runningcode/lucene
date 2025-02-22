/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.document;

import java.io.IOException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.search.CheckHits;
import org.apache.lucene.tests.search.QueryUtils;
import org.apache.lucene.tests.util.LuceneTestCase;

public class TestLongDistanceFeatureQuery extends LuceneTestCase {

  public void testEqualsAndHashcode() {
    Query q1 = LongField.newDistanceFeatureQuery("foo", 3, 10, 5);
    Query q2 = LongField.newDistanceFeatureQuery("foo", 3, 10, 5);
    QueryUtils.checkEqual(q1, q2);

    Query q3 = LongField.newDistanceFeatureQuery("bar", 3, 10, 5);
    QueryUtils.checkUnequal(q1, q3);

    Query q4 = LongField.newDistanceFeatureQuery("foo", 4, 10, 5);
    QueryUtils.checkUnequal(q1, q4);

    Query q5 = LongField.newDistanceFeatureQuery("foo", 3, 9, 5);
    QueryUtils.checkUnequal(q1, q5);

    Query q6 = LongField.newDistanceFeatureQuery("foo", 3, 10, 6);
    QueryUtils.checkUnequal(q1, q6);
  }

  public void testBasics() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter w =
        new RandomIndexWriter(
            random(),
            dir,
            newIndexWriterConfig().setMergePolicy(newLogMergePolicy(random().nextBoolean())));
    Document doc = new Document();
    LongField field = new LongField("foo", 0L);
    doc.add(field);

    field.setLongValue(3);
    w.addDocument(doc);

    field.setLongValue(12);
    w.addDocument(doc);

    field.setLongValue(8);
    w.addDocument(doc);

    field.setLongValue(-1);
    w.addDocument(doc);

    field.setLongValue(7);
    w.addDocument(doc);

    DirectoryReader reader = w.getReader();
    IndexSearcher searcher = newSearcher(reader);

    Query q = LongField.newDistanceFeatureQuery("foo", 3, 10, 5);
    CollectorManager<TopScoreDocCollector, TopDocs> manager =
        TopScoreDocCollector.createSharedManager(2, null, 1);
    TopDocs topHits = searcher.search(q, manager);
    assertEquals(2, topHits.scoreDocs.length);

    CheckHits.checkEqual(
        q,
        new ScoreDoc[] {
          new ScoreDoc(1, (float) (3f * (5. / (5. + 2.)))),
          new ScoreDoc(2, (float) (3f * (5. / (5. + 2.))))
        },
        topHits.scoreDocs);

    q = LongField.newDistanceFeatureQuery("foo", 3, 7, 5);
    manager = TopScoreDocCollector.createSharedManager(2, null, 1);
    topHits = searcher.search(q, manager);
    assertEquals(2, topHits.scoreDocs.length);
    CheckHits.checkExplanations(q, "", searcher);

    CheckHits.checkEqual(
        q,
        new ScoreDoc[] {
          new ScoreDoc(4, (float) (3f * (5. / (5. + 0.)))),
          new ScoreDoc(2, (float) (3f * (5. / (5. + 1.))))
        },
        topHits.scoreDocs);

    reader.close();
    w.close();
    dir.close();
  }

  public void testOverUnderFlow() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter w =
        new RandomIndexWriter(
            random(),
            dir,
            newIndexWriterConfig().setMergePolicy(newLogMergePolicy(random().nextBoolean())));
    Document doc = new Document();
    LongField field = new LongField("foo", 0L);
    doc.add(field);

    field.setLongValue(3);
    w.addDocument(doc);

    field.setLongValue(12);
    w.addDocument(doc);

    field.setLongValue(-10);
    w.addDocument(doc);

    field.setLongValue(Long.MAX_VALUE);
    w.addDocument(doc);

    field.setLongValue(Long.MIN_VALUE);
    w.addDocument(doc);

    DirectoryReader reader = w.getReader();
    IndexSearcher searcher = newSearcher(reader);

    Query q = LongField.newDistanceFeatureQuery("foo", 3, Long.MAX_VALUE - 1, 100);
    CollectorManager<TopScoreDocCollector, TopDocs> manager =
        TopScoreDocCollector.createSharedManager(2, null, 1);
    TopDocs topHits = searcher.search(q, manager);
    assertEquals(2, topHits.scoreDocs.length);

    CheckHits.checkEqual(
        q,
        new ScoreDoc[] {
          new ScoreDoc(3, (float) (3f * (100. / (100. + 1.)))),
          new ScoreDoc(
              0,
              (float)
                  (3f
                      * (100.
                          / (100.
                              + Long
                                  .MAX_VALUE)))) // rounding makes the distance treated as if it was
          // MAX_VALUE
        },
        topHits.scoreDocs);

    q = LongField.newDistanceFeatureQuery("foo", 3, Long.MIN_VALUE + 1, 100);
    topHits = searcher.search(q, manager);
    assertEquals(2, topHits.scoreDocs.length);
    CheckHits.checkExplanations(q, "", searcher);

    CheckHits.checkEqual(
        q,
        new ScoreDoc[] {
          new ScoreDoc(4, (float) (3f * (100. / (100. + 1.)))),
          new ScoreDoc(
              0,
              (float)
                  (3f
                      * (100.
                          / (100.
                              + Long
                                  .MAX_VALUE)))) // rounding makes the distance treated as if it was
          // MAX_VALUE
        },
        topHits.scoreDocs);

    reader.close();
    w.close();
    dir.close();
  }

  public void testMissingField() throws IOException {
    IndexReader reader = new MultiReader();
    IndexSearcher searcher = newSearcher(reader);

    Query q = LongField.newDistanceFeatureQuery("foo", 3, 10, 5);
    TopDocs topHits = searcher.search(q, 2);
    assertEquals(0, topHits.totalHits.value);
  }

  public void testMissingValue() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter w =
        new RandomIndexWriter(
            random(),
            dir,
            newIndexWriterConfig().setMergePolicy(newLogMergePolicy(random().nextBoolean())));
    Document doc = new Document();
    LongField field = new LongField("foo", 0L);
    doc.add(field);

    field.setLongValue(3);
    w.addDocument(doc);

    w.addDocument(new Document());

    field.setLongValue(7);
    w.addDocument(doc);

    DirectoryReader reader = w.getReader();
    IndexSearcher searcher = newSearcher(reader);

    Query q = LongField.newDistanceFeatureQuery("foo", 3, 10, 5);
    CollectorManager<TopScoreDocCollector, TopDocs> manager =
        TopScoreDocCollector.createSharedManager(3, null, 1);
    TopDocs topHits = searcher.search(q, manager);
    assertEquals(2, topHits.scoreDocs.length);

    CheckHits.checkEqual(
        q,
        new ScoreDoc[] {
          new ScoreDoc(2, (float) (3f * (5. / (5. + 3.)))),
          new ScoreDoc(0, (float) (3f * (5. / (5. + 7.))))
        },
        topHits.scoreDocs);

    CheckHits.checkExplanations(q, "", searcher);

    reader.close();
    w.close();
    dir.close();
  }

  public void testMultiValued() throws IOException {
    Directory dir = newDirectory();
    RandomIndexWriter w =
        new RandomIndexWriter(
            random(),
            dir,
            newIndexWriterConfig().setMergePolicy(newLogMergePolicy(random().nextBoolean())));

    Document doc = new Document();
    for (long v : new long[] {3, 1000, Long.MAX_VALUE}) {
      doc.add(new LongField("foo", v));
    }
    w.addDocument(doc);

    doc = new Document();
    for (long v : new long[] {-100, 12, 999}) {
      doc.add(new LongField("foo", v));
    }
    w.addDocument(doc);

    doc = new Document();
    for (long v : new long[] {Long.MIN_VALUE, -1000, 8}) {
      doc.add(new LongField("foo", v));
    }
    w.addDocument(doc);

    doc = new Document();
    for (long v : new long[] {-1}) {
      doc.add(new LongField("foo", v));
    }
    w.addDocument(doc);

    doc = new Document();
    for (long v : new long[] {Long.MIN_VALUE, 7}) {
      doc.add(new LongField("foo", v));
    }
    w.addDocument(doc);

    DirectoryReader reader = w.getReader();
    IndexSearcher searcher = newSearcher(reader);

    Query q = LongField.newDistanceFeatureQuery("foo", 3, 10, 5);
    CollectorManager<TopScoreDocCollector, TopDocs> manager =
        TopScoreDocCollector.createSharedManager(2, null, 1);
    TopDocs topHits = searcher.search(q, manager);
    assertEquals(2, topHits.scoreDocs.length);

    CheckHits.checkEqual(
        q,
        new ScoreDoc[] {
          new ScoreDoc(1, (float) (3f * (5. / (5. + 2.)))),
          new ScoreDoc(2, (float) (3f * (5. / (5. + 2.))))
        },
        topHits.scoreDocs);

    q = LongField.newDistanceFeatureQuery("foo", 3, 7, 5);
    manager = TopScoreDocCollector.createSharedManager(2, null, 1);
    topHits = searcher.search(q, manager);
    assertEquals(2, topHits.scoreDocs.length);
    CheckHits.checkExplanations(q, "", searcher);

    CheckHits.checkEqual(
        q,
        new ScoreDoc[] {
          new ScoreDoc(4, (float) (3f * (5. / (5. + 0.)))),
          new ScoreDoc(2, (float) (3f * (5. / (5. + 1.))))
        },
        topHits.scoreDocs);

    reader.close();
    w.close();
    dir.close();
  }

  public void testRandom() throws IOException {
    Directory dir = newDirectory();
    IndexWriter w =
        new IndexWriter(
            dir, newIndexWriterConfig().setMergePolicy(newLogMergePolicy(random().nextBoolean())));
    Document doc = new Document();
    LongField field = new LongField("foo", 0L);
    doc.add(field);

    int numDocs = atLeast(10000);
    for (int i = 0; i < numDocs; ++i) {
      long v = random().nextLong();
      field.setLongValue(v);
      w.addDocument(doc);
    }

    IndexReader reader = DirectoryReader.open(w);
    IndexSearcher searcher = newSearcher(reader);

    for (int iter = 0; iter < 10; ++iter) {
      long origin = random().nextLong();
      long pivotDistance;
      do {
        pivotDistance = random().nextLong();
      } while (pivotDistance <= 0);
      float boost = (1 + random().nextInt(10)) / 3f;
      Query q = LongField.newDistanceFeatureQuery("foo", boost, origin, pivotDistance);

      CheckHits.checkTopScores(random(), q, searcher);
    }

    reader.close();
    w.close();
    dir.close();
  }
}
