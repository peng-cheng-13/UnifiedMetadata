private static JavaSerializer serializer = new JavaSerializer< JournalEntry>();
byte[] tmpdata = JavaSerializer .serialize(entry.toBuilder().setSequenceNumber(mNextSequenceNumber).build());
DB.put(filename, tmpdata);
