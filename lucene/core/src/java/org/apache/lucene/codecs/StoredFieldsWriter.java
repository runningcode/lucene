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
package org.apache.lucene.codecs;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DocIDMerger;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.IndexableFieldType;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.BytesRef;

/**
 * Codec API for writing stored fields:
 *
 * <ol>
 *   <li>For every document, {@link #startDocument()} is called, informing the Codec that a new
 *       document has started.
 *   <li>{@link #writeField(FieldInfo, IndexableField)} is called for each field in the document.
 *   <li>After all documents have been written, {@link #finish(int)} is called for
 *       verification/sanity-checks.
 *   <li>Finally the writer is closed ({@link #close()})
 * </ol>
 *
 * @lucene.experimental
 */
public abstract class StoredFieldsWriter implements Closeable, Accountable {

  /** Sole constructor. (For invocation by subclass constructors, typically implicit.) */
  protected StoredFieldsWriter() {}

  /**
   * Called before writing the stored fields of the document. {@link #writeField(FieldInfo,
   * IndexableField)} will be called for each stored field. Note that this is called even if the
   * document has no stored fields.
   */
  public abstract void startDocument() throws IOException;

  /** Called when a document and all its fields have been added. */
  public void finishDocument() throws IOException {}

  /** Writes a single stored field. */
  public abstract void writeField(FieldInfo info, IndexableField field) throws IOException;

  /**
   * Called before {@link #close()}, passing in the number of documents that were written. Note that
   * this is intentionally redundant (equivalent to the number of calls to {@link #startDocument()},
   * but a Codec should check that this is the case to detect the JRE bug described in LUCENE-1282.
   */
  public abstract void finish(int numDocs) throws IOException;

  private static class StoredFieldsMergeSub extends DocIDMerger.Sub {
    private final StoredFieldsReader reader;
    private final int maxDoc;
    private final MergeVisitor visitor;
    int docID = -1;

    public StoredFieldsMergeSub(
        MergeVisitor visitor, MergeState.DocMap docMap, StoredFieldsReader reader, int maxDoc) {
      super(docMap);
      this.maxDoc = maxDoc;
      this.reader = reader;
      this.visitor = visitor;
    }

    @Override
    public int nextDoc() {
      docID++;
      if (docID == maxDoc) {
        return NO_MORE_DOCS;
      } else {
        return docID;
      }
    }
  }

  /**
   * Merges in the stored fields from the readers in <code>mergeState</code>. The default
   * implementation skips over deleted documents, and uses {@link #startDocument()}, {@link
   * #writeField(FieldInfo, IndexableField)}, and {@link #finish(int)}, returning the number of
   * documents that were written. Implementations can override this method for more sophisticated
   * merging (bulk-byte copying, etc).
   */
  public int merge(MergeState mergeState) throws IOException {
    List<StoredFieldsMergeSub> subs = new ArrayList<>();
    for (int i = 0; i < mergeState.storedFieldsReaders.length; i++) {
      StoredFieldsReader storedFieldsReader = mergeState.storedFieldsReaders[i];
      storedFieldsReader.checkIntegrity();
      subs.add(
          new StoredFieldsMergeSub(
              new MergeVisitor(mergeState, i),
              mergeState.docMaps[i],
              storedFieldsReader,
              mergeState.maxDocs[i]));
    }

    final DocIDMerger<StoredFieldsMergeSub> docIDMerger =
        DocIDMerger.of(subs, mergeState.needsIndexSort);

    int docCount = 0;
    while (true) {
      StoredFieldsMergeSub sub = docIDMerger.next();
      if (sub == null) {
        break;
      }
      assert sub.mappedDocID == docCount;
      startDocument();
      sub.reader.document(sub.docID, sub.visitor);
      finishDocument();
      docCount++;
    }
    finish(docCount);
    return docCount;
  }

  /**
   * A visitor that adds every field it sees.
   *
   * <p>Use like this:
   *
   * <pre>
   * MergeVisitor visitor = new MergeVisitor(mergeState, readerIndex);
   * for (...) {
   *   startDocument();
   *   storedFieldsReader.document(docID, visitor);
   *   finishDocument();
   * }
   * </pre>
   */
  protected class MergeVisitor extends StoredFieldVisitor implements IndexableField {
    BytesRef binaryValue;
    String stringValue;
    Number numericValue;
    FieldInfo currentField;
    FieldInfos remapper;

    /** Create new merge visitor. */
    public MergeVisitor(MergeState mergeState, int readerIndex) {
      // if field numbers are aligned, we can save hash lookups
      // on every field access. Otherwise, we need to lookup
      // fieldname each time, and remap to a new number.
      for (FieldInfo fi : mergeState.fieldInfos[readerIndex]) {
        FieldInfo other = mergeState.mergeFieldInfos.fieldInfo(fi.number);
        if (other == null || !other.name.equals(fi.name)) {
          remapper = mergeState.mergeFieldInfos;
          break;
        }
      }
    }

    @Override
    public void binaryField(FieldInfo fieldInfo, byte[] value) throws IOException {
      reset(fieldInfo);
      // TODO: can we avoid new BR here?
      binaryValue = new BytesRef(value);
      write();
    }

    @Override
    public void stringField(FieldInfo fieldInfo, String value) throws IOException {
      reset(fieldInfo);
      stringValue = Objects.requireNonNull(value, "String value should not be null");
      write();
    }

    @Override
    public void intField(FieldInfo fieldInfo, int value) throws IOException {
      reset(fieldInfo);
      numericValue = value;
      write();
    }

    @Override
    public void longField(FieldInfo fieldInfo, long value) throws IOException {
      reset(fieldInfo);
      numericValue = value;
      write();
    }

    @Override
    public void floatField(FieldInfo fieldInfo, float value) throws IOException {
      reset(fieldInfo);
      numericValue = value;
      write();
    }

    @Override
    public void doubleField(FieldInfo fieldInfo, double value) throws IOException {
      reset(fieldInfo);
      numericValue = value;
      write();
    }

    @Override
    public Status needsField(FieldInfo fieldInfo) throws IOException {
      return Status.YES;
    }

    @Override
    public String name() {
      return currentField.name;
    }

    @Override
    public IndexableFieldType fieldType() {
      return StoredField.TYPE;
    }

    @Override
    public BytesRef binaryValue() {
      return binaryValue;
    }

    @Override
    public String stringValue() {
      return stringValue;
    }

    @Override
    public Number numericValue() {
      return numericValue;
    }

    @Override
    public Reader readerValue() {
      return null;
    }

    @Override
    public TokenStream tokenStream(Analyzer analyzer, TokenStream reuse) {
      return null;
    }

    void reset(FieldInfo field) {
      if (remapper != null) {
        // field numbers are not aligned, we need to remap to the new field number
        currentField = remapper.fieldInfo(field.name);
      } else {
        currentField = field;
      }
      binaryValue = null;
      stringValue = null;
      numericValue = null;
    }

    void write() throws IOException {
      writeField(currentField, this);
    }
  }

  @Override
  public abstract void close() throws IOException;
}
