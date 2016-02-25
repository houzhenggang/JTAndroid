package com.utils.note.rteditor.utils;


import java.io.IOException;
import java.io.OutputStream;

public class ClosedOutputStream extends OutputStream {
 public static final ClosedOutputStream CLOSED_OUTPUT_STREAM = new ClosedOutputStream();

 public ClosedOutputStream() {
 }

 public void write(int b) throws IOException {
     throw new IOException("write(" + b + ") failed: stream is closed");
 }
}

