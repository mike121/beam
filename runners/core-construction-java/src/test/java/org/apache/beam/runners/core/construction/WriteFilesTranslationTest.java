/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.runners.core.construction;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.beam.sdk.common.runner.v1.RunnerApi;
import org.apache.beam.sdk.common.runner.v1.RunnerApi.ParDoPayload;
import org.apache.beam.sdk.io.DynamicFileDestinations;
import org.apache.beam.sdk.io.FileBasedSink;
import org.apache.beam.sdk.io.FileBasedSink.FilenamePolicy;
import org.apache.beam.sdk.io.FileBasedSink.OutputFileHints;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.WriteFiles;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.runners.AppliedPTransform;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.SerializableFunctions;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.Suite;

/** Tests for {@link WriteFilesTranslation}. */
@RunWith(Suite.class)
@Suite.SuiteClasses({
  WriteFilesTranslationTest.TestWriteFilesPayloadTranslation.class,
})
public class WriteFilesTranslationTest {

  /** Tests for translating various {@link ParDo} transforms to/from {@link ParDoPayload} protos. */
  @RunWith(Parameterized.class)
  public static class TestWriteFilesPayloadTranslation {
    @Parameters(name = "{index}: {0}")
    public static Iterable<WriteFiles<Object, Void, Object>> data() {
      SerializableFunction<Object, Object> format = SerializableFunctions.constant(null);
      return ImmutableList.of(
          WriteFiles.to(new DummySink(), format),
          WriteFiles.to(new DummySink(), format).withWindowedWrites(),
          WriteFiles.to(new DummySink(), format).withNumShards(17),
          WriteFiles.to(new DummySink(), format).withWindowedWrites().withNumShards(42));
    }

    @Parameter(0)
    public WriteFiles<String, Void, String> writeFiles;

    public static TestPipeline p = TestPipeline.create().enableAbandonedNodeEnforcement(false);

    @Test
    public void testEncodedProto() throws Exception {
      RunnerApi.WriteFilesPayload payload = WriteFilesTranslation.toProto(writeFiles);

      assertThat(
          payload.getRunnerDeterminedSharding(),
          equalTo(writeFiles.getNumShards() == null && writeFiles.getSharding() == null));

      assertThat(payload.getWindowedWrites(), equalTo(writeFiles.isWindowedWrites()));

      assertThat(
          (FileBasedSink<String, Void>) WriteFilesTranslation.sinkFromProto(payload.getSink()),
          equalTo(writeFiles.getSink()));
    }

    @Test
    public void testExtractionDirectFromTransform() throws Exception {
      PCollection<String> input = p.apply(Create.of("hello"));
      PDone output = input.apply(writeFiles);

      AppliedPTransform<PCollection<String>, PDone, WriteFiles<String, Void, String>>
          appliedPTransform =
              AppliedPTransform.of("foo", input.expand(), output.expand(), writeFiles, p);

      assertThat(
          WriteFilesTranslation.isRunnerDeterminedSharding(appliedPTransform),
          equalTo(writeFiles.getNumShards() == null && writeFiles.getSharding() == null));

      assertThat(
          WriteFilesTranslation.isWindowedWrites(appliedPTransform),
          equalTo(writeFiles.isWindowedWrites()));

      assertThat(
          WriteFilesTranslation.<String, Void, String>getSink(appliedPTransform),
          equalTo(writeFiles.getSink()));
    }
  }

  /**
   * A simple {@link FileBasedSink} for testing serialization/deserialization. Not mocked to avoid
   * any issues serializing mocks.
   */
  private static class DummySink extends FileBasedSink<Object, Void> {

    DummySink() {
      super(
          StaticValueProvider.of(FileSystems.matchNewResource("nowhere", false)),
          DynamicFileDestinations.constant(new DummyFilenamePolicy()));
    }

    @Override
    public WriteOperation<Object, Void> createWriteOperation() {
      return new DummyWriteOperation(this);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof DummySink)) {
        return false;
      }

      DummySink that = (DummySink) other;

      return getTempDirectoryProvider().isAccessible()
          && that.getTempDirectoryProvider().isAccessible()
          && getTempDirectoryProvider().get().equals(that.getTempDirectoryProvider().get());
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          DummySink.class,
          getTempDirectoryProvider().isAccessible() ? getTempDirectoryProvider().get() : null);
    }
  }

  private static class DummyWriteOperation extends FileBasedSink.WriteOperation<Object, Void> {
    public DummyWriteOperation(FileBasedSink<Object, Void> sink) {
      super(sink);
    }

    @Override
    public FileBasedSink.Writer<Object, Void> createWriter() throws Exception {
      throw new UnsupportedOperationException("Should never be called.");
    }
  }

  private static class DummyFilenamePolicy extends FilenamePolicy {
    @Override
    public ResourceId windowedFilename(WindowedContext c, OutputFileHints outputFileHints) {
      throw new UnsupportedOperationException("Should never be called.");
    }

    @Nullable
    @Override
    public ResourceId unwindowedFilename(Context c, OutputFileHints outputFileHints) {
      throw new UnsupportedOperationException("Should never be called.");
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof DummyFilenamePolicy;
    }

    @Override
    public int hashCode() {
      return DummyFilenamePolicy.class.hashCode();
    }
  }
}
