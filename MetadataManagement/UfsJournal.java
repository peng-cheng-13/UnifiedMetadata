/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.journal.ufs;

import static org.iq80.leveldb.impl.Iq80DBFactory.factory;

import alluxio.Configuration;
import alluxio.PropertyKey;
import alluxio.master.journal.Journal;
import alluxio.master.journal.JournalEntryStateMachine;
import alluxio.master.journal.JournalReader;
import alluxio.proto.journal.Journal.JournalEntry;
import alluxio.underfs.UfsStatus;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.UnderFileSystemConfiguration;
import alluxio.underfs.options.DeleteOptions;
import alluxio.util.URIUtils;
import alluxio.util.UnderFileSystemUtils;
import alluxio.proto.journal.File.InodeFileEntry;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.SeekingIteratorAdapter;
import org.iq80.leveldb.impl.SeekingIteratorAdapter.DbEntry;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.nio.ByteBuffer;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Implementation of UFS-based journal.
 *
 * The journal is made up of 2 components:
 * - The checkpoint:  a snapshot of the master state
 * - The log entries: incremental entries to apply to the checkpoint.
 *
 * The journal log entries must be self-contained. Checkpoint is considered as a compaction of
 * a set of journal log entries. If the master does not do any checkpoint, the journal should
 * still be sufficient.
 *
 * Journal file structure:
 * journal_folder/version/logs/StartSequenceNumber-EndSequenceNumber
 * journal_folder/version/checkpoints/0-EndSequenceNumber
 * journal_folder/version/.tmp/random_id
 */
@ThreadSafe
public class UfsJournal implements Journal {
  private static final Logger LOG = LoggerFactory.getLogger(UfsJournal.class);
  /**
   * This is set to Long.MAX_VALUE such that the current log can be sorted after any other
   * completed logs.
   */
  public static final long UNKNOWN_SEQUENCE_NUMBER = Long.MAX_VALUE;
  /** The journal version. */
  public static final String VERSION = "v1";

  /** Directory for journal edit logs including the incomplete log file. */
  private static final String LOG_DIRNAME = "logs";
  /** Directory for committed checkpoints. */
  private static final String CHECKPOINT_DIRNAME = "checkpoints";
  /** Directory for temporary files. */
  private static final String TMP_DIRNAME = ".tmp";

  private final URI mLogDir;
  private final URI mCheckpointDir;
  private final URI mTmpDir;

  /** The location where this journal is stored. */
  private final URI mLocation;
  /** The state machine managed by this journal. */
  private final JournalEntryStateMachine mMaster;
  /** The UFS where the journal is being written to. */
  private final UnderFileSystem mUfs;
  /** The amount of time to wait to pass without seeing a new journal entry when gaining primacy. */
  private final long mQuietPeriodMs;
  /** The current log writer. Null when in secondary mode. */
  private UfsJournalLogWriter mWriter;
  /**
   * Thread for tailing the journal, taking snapshots, and applying updates to the state machine.
   * Null when in primary mode.
   */
  private UfsJournalCheckpointThread mTailerThread;

  /** The current Database that manages the entry as KV pairs.*/
  private DB mEntryDB;
  /** Serializer that transforms entry to byte array.*/
  private JavaSerializer mSerializer;

  /**
   * @return the ufs configuration to use for the journal operations
   */
  protected static UnderFileSystemConfiguration getJournalUfsConf() {
    Map<String, String> ufsConf =
        Configuration.getNestedProperties(PropertyKey.MASTER_JOURNAL_UFS_OPTION);
    return UnderFileSystemConfiguration.defaults().setUserSpecifiedConf(ufsConf);
  }

  /**
   * Creates a new instance of {@link UfsJournal}.
   *
   * @param location the location for this journal
   * @param stateMachine the state machine to manage
   * @param quietPeriodMs the amount of time to wait to pass without seeing a new journal entry when
   *        gaining primacy
   */
  public UfsJournal(URI location, JournalEntryStateMachine stateMachine, long quietPeriodMs) {
    this(location, stateMachine,
        UnderFileSystem.Factory.create(location.toString(), getJournalUfsConf()), quietPeriodMs);
  }

  /**
   * Creates a new instance of {@link UfsJournal}.
   *
   * @param location the location for this journal
   * @param stateMachine the state machine to manage
   * @param ufs the under file system
   * @param quietPeriodMs the amount of time to wait to pass without seeing a new journal entry when
   *        gaining primacy
   */
  UfsJournal(URI location, JournalEntryStateMachine stateMachine, UnderFileSystem ufs,
      long quietPeriodMs) {
    mLocation = URIUtils.appendPathOrDie(location, VERSION);
    mMaster = stateMachine;
    mUfs = ufs;
    mQuietPeriodMs = quietPeriodMs;

    mLogDir = URIUtils.appendPathOrDie(mLocation, LOG_DIRNAME);
    mCheckpointDir = URIUtils.appendPathOrDie(mLocation, CHECKPOINT_DIRNAME);
    mTmpDir = URIUtils.appendPathOrDie(mLocation, TMP_DIRNAME);
    String storepath = location.getPath();
    LOG.info("DB Test: Init UfsJounalDB, Journal path: {}", storepath);
    File targetfile;
    try {
      targetfile = new File(storepath.concat("/EntryStore"));
      LOG.info("DB Test: Create DB path: {}", storepath);
      Options options = new Options();
      options.createIfMissing(true);
      mEntryDB = factory.open(targetfile, options);
      LOG.info("DB Test: Create LevelDB-Entry succeeded");
      mSerializer = new JavaSerializer<JournalEntry>();
      LOG.info("DB Test: Init JavaSerializer");
    } catch (IOException e) {
      LOG.info("Init DB failed");
    }
  }

  @Override
  public URI getLocation() {
    return mLocation;
  }

  @Override
  public void write(JournalEntry entry) throws IOException {
    writer().write(entry);
    ByteBuffer tmpbuffer = ByteBuffer.allocate(8);
    //JournalEntry tmpentry = entry.toBuilder().build();
    JournalEntry tmpentry = entry;
    if (tmpentry.hasInodeFile()) {
      InodeFileEntry fileentry = tmpentry.getInodeFile();
      long fileid = fileentry.getId();
      tmpbuffer.putLong(fileid);
      mEntryDB.put(tmpbuffer.array(), mSerializer.serialize(tmpentry));
      LOG.info("DB Test: put KV pair to EntryDB once with fileid {}", fileid);
      LOG.info("File Name: {}", fileentry.getName());
      LOG.info("BlockIds: {}", fileentry.getBlocksList());
      LOG.info("Cacheable: {}", fileentry.getCacheable());
      LOG.info("{}, {},", fileentry.getCompleted(), fileentry.getCreationTimeMs());
      LOG.info("{}, {}", fileentry.getLastModificationTimeMs(), fileentry.getLength());
      LOG.info("{}, {}", fileentry.getParentId(), fileentry.getPinned());
      LOG.info("{}, {},", fileentry.getOwner(), fileentry.getGroup());
      LOG.info("{}, {},", fileentry.getTtl(), fileentry.getMode());
    } else if (tmpentry.hasInodeDirectory()) {
      long dirid = tmpentry.getInodeDirectory().getId();
      tmpbuffer.putLong(dirid);
      mEntryDB.put(tmpbuffer.array(), mSerializer.serialize(tmpentry));
      LOG.info("DB Test: put DIR KV pair to EntryDB once with fileid {}", dirid);
    }
  }

  @Override
  public void flush() throws IOException {
    writer().flush();
  }

  private UfsJournalLogWriter writer() throws IOException {
    if (mWriter == null) {
      throw new IllegalStateException("Cannot write to the journal in secondary mode");
    }
    return mWriter;
  }

  /**
   * Starts the journal in secondary mode.
   */
  public void start() throws IOException {
    mMaster.resetState();
    mTailerThread = new UfsJournalCheckpointThread(mMaster, this);
    mTailerThread.start();
  }

  /**
   * Transitions the journal from secondary to primary mode. The journal will apply the latest
   * journal entries to the state machine, then begin to allow writes.
   */
  public void gainPrimacy() throws IOException {
    Preconditions.checkState(mWriter == null, "writer must be null in secondary mode");
    Preconditions.checkState(mTailerThread != null,
        "tailer thread must not be null in secondary mode");
    mTailerThread.awaitTermination(true);
    long nextSequenceNumber = mTailerThread.getNextSequenceNumber();
    mTailerThread = null;
    nextSequenceNumber = catchUp(nextSequenceNumber);
    mWriter = new UfsJournalLogWriter(this, nextSequenceNumber);
  }

  /**
   * Transitions the journal from primary to secondary mode. The journal will no longer allow
   * writes, and the state machine is rebuilt from the journal and kept up to date.
   */
  public void losePrimacy() throws IOException {
    Preconditions.checkState(mWriter != null, "writer thread must not be null in primary mode");
    Preconditions.checkState(mTailerThread == null, "tailer thread must be null in primary mode");
    mWriter.close();
    mWriter = null;
    mMaster.resetState();
    mTailerThread = new UfsJournalCheckpointThread(mMaster, this);
    mTailerThread.start();
  }

  /**
   * @return the quiet period for this journal
   */
  public long getQuietPeriodMs() {
    return mQuietPeriodMs;
  }

  /**
   * @param readIncompleteLogs whether the reader should read the latest incomplete log
   * @return a reader for reading from the start of the journal
   */
  public UfsJournalReader getReader(boolean readIncompleteLogs) {
    return new UfsJournalReader(this, readIncompleteLogs);
  }

  /**
   * @param checkpointSequenceNumber the next sequence number after the checkpoint
   * @return a writer for writing a checkpoint
   */
  public UfsJournalCheckpointWriter getCheckpointWriter(long checkpointSequenceNumber)
      throws IOException {
    return new UfsJournalCheckpointWriter(this, checkpointSequenceNumber);
  }

  /**
   * @return the first log sequence number that hasn't yet been checkpointed
   */
  public long getNextSequenceNumberToCheckpoint() throws IOException {
    return UfsJournalSnapshot.getNextLogSequenceNumberToCheckpoint(this);
  }

  /**
   * @return whether the journal has been formatted
   */
  public boolean isFormatted() throws IOException {
    UfsStatus[] files = mUfs.listStatus(mLocation.toString());
    if (files == null) {
      return false;
    }
    // Search for the format file.
    String formatFilePrefix = Configuration.get(PropertyKey.MASTER_FORMAT_FILE_PREFIX);
    for (UfsStatus file : files) {
      if (file.getName().startsWith(formatFilePrefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Formats the journal.
   */
  public void format() throws IOException {
    URI location = getLocation();
    LOG.info("Formatting {}", location);
    if (mUfs.isDirectory(location.toString())) {
      for (UfsStatus status : mUfs.listStatus(location.toString())) {
        String childPath = URIUtils.appendPathOrDie(location, status.getName()).toString();
        if (status.isDirectory()
            && !mUfs.deleteDirectory(childPath, DeleteOptions.defaults().setRecursive(true))
            || status.isFile() && !mUfs.deleteFile(childPath)) {
          throw new IOException(String.format("Failed to delete %s", childPath));
        }
      }
    } else if (!mUfs.mkdirs(location.toString())) {
      throw new IOException(String.format("Failed to create %s", location));
    }

    // Create a breadcrumb that indicates that the journal folder has been formatted.
    UnderFileSystemUtils.touch(mUfs, URIUtils.appendPathOrDie(location,
        Configuration.get(PropertyKey.MASTER_FORMAT_FILE_PREFIX) + System.currentTimeMillis())
        .toString());
  }

  /**
   * @return the log directory location
   */
  URI getLogDir() {
    return mLogDir;
  }

  /**
   * @return the checkpoint directory location
   */
  URI getCheckpointDir() {
    return mCheckpointDir;
  }

  /**
   * @return the temporary directory location
   */
  URI getTmpDir() {
    return mTmpDir;
  }

  /**
   * @return the under file system instance
   */
  UnderFileSystem getUfs() {
    return mUfs;
  }

  /**
   * Reads and applies all journal entries starting from the specified sequence number.
   *
   * @param nextSequenceNumber the sequence number to continue catching up from
   * @return the next sequence number after the final sequence number read
   */
  private long catchUp(long nextSequenceNumber) {
    try (JournalReader journalReader = new UfsJournalReader(this, nextSequenceNumber, true)) {
      JournalEntry entry;
      SeekingIteratorAdapter dbiterator = (SeekingIteratorAdapter ) mEntryDB.iterator();
      DbEntry tmpentry;
      LOG.info("DB Test: Init DB Iterator");
      ByteBuffer buffer;
      long fileid;
      while (dbiterator.hasNext()) {
        tmpentry = dbiterator.next();
        byte[] tmpkey = tmpentry.getKey();
        buffer = ByteBuffer.wrap(tmpkey);
        fileid = buffer.getLong();
        LOG.info("Read file entry once, fileID: {}", fileid);
        byte[] tmpvalue = tmpentry.getValue();
        //entry = JournalEntry.parseFrom(tmpentry.getValue());
        entry = (JournalEntry) mSerializer.deserialize(tmpvalue);
        if (entry != null) {
          if (entry.hasInodeFile()) {
            InodeFileEntry fileentry = entry.getInodeFile();
            LOG.info("File Name: {}", fileentry.getName());
            LOG.info("BlockIds: {}", fileentry.getBlocksList());
            LOG.info("Cacheable: {}", fileentry.getCacheable());
            LOG.info("{}, {},", fileentry.getCompleted(), fileentry.getCreationTimeMs());
            LOG.info("{}, {}", fileentry.getLastModificationTimeMs(), fileentry.getLength());
            LOG.info("{}, {}", fileentry.getParentId(), fileentry.getPinned());
            LOG.info("{}, {},", fileentry.getOwner(), fileentry.getGroup());
            LOG.info("{}, {},", fileentry.getTtl(), fileentry.getMode());
          }
          mMaster.processJournalEntry(entry);
        }
      }
      /*while ((entry = journalReader.read()) != null) {
        if (entry.hasInodeFile()) {
          InodeFileEntry fileentry = entry.getInodeFile();
          LOG.info("File Name: {}", fileentry.getName());
          LOG.info("BlockIds: {}", fileentry.getBlocksList());
          LOG.info("Cacheable: {}", fileentry.getCacheable());
          LOG.info("{}, {},", fileentry.getCompleted(), fileentry.getCreationTimeMs());
          LOG.info("{}, {}", fileentry.getLastModificationTimeMs(), fileentry.getLength());
          LOG.info("{}, {}", fileentry.getParentId(), fileentry.getPinned());
          LOG.info("{}, {},", fileentry.getOwner(), fileentry.getGroup());
          LOG.info("{}, {},", fileentry.getTtl(), fileentry.getMode());
        }
        //mMaster.processJournalEntry(entry);
      }*/
      return journalReader.getNextSequenceNumber();
    } catch (IOException e) {
      LOG.error("{}: Failed to read from journal", mMaster.getName(), e);
      throw new RuntimeException(e);
    } /* catch (InvalidJournalEntryException e) {
      LOG.error("{}: Invalid journal entry detected.", mMaster.getName(), e);
      // We found an invalid journal entry, nothing we can do but crash.
      throw new RuntimeException(e);
    }*/
  }

  @Override
  public String toString() {
    return "UfsJournal(" + mLocation + ")";
  }

  @Override
  public void close() throws IOException {
    mEntryDB.close();
    LOG.info("Close EntryDB");
    if (mWriter != null) {
      mWriter.close();
      mWriter = null;
    }
    if (mTailerThread != null) {
      mTailerThread.awaitTermination(false);
      mTailerThread = null;
    }
  }
}
