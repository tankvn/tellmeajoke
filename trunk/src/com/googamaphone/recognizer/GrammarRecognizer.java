package com.googamaphone.recognizer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.Context;
import android.os.Bundle;
import android.speech.srec.MicrophoneInputStream;
import android.speech.srec.Recognizer;
import android.speech.srec.Recognizer.Grammar;
import android.util.Config;
import android.util.Log;

public class GrammarRecognizer {
	private static final String TAG = "GrammarRecognizer";
	private static final String SREC_DIR = Recognizer.getConfigDir(null);
	private static final int SAMPLE_RATE = 11025;
	private static final int RESULT_LIMIT = 5;

	public static final String KEY_CONFIDENCE = Recognizer.KEY_CONFIDENCE;
	public static final String KEY_LITERAL = Recognizer.KEY_LITERAL;
	public static final String KEY_MEANING = Recognizer.KEY_MEANING;

	@SuppressWarnings("unused")
	private WeakReference<Context> mContext;
	private File mGrammar;
	private Recognizer mSrec;
	private Recognizer.Grammar mSrecGrammar;
	private GrammarListener mListener;
	private Thread mThread;

	public GrammarRecognizer(Context context) throws IOException {
		mContext = new WeakReference<Context>(context);
		mSrec = new Recognizer(SREC_DIR + "/baseline11k.par");
	}

	public void shutdown() {
		try {
  		if (mThread != null)
  			mThread.interrupt();
  		mSrecGrammar.destroy();
  		mSrec.destroy();
		} catch (IllegalStateException e) {
			e.printStackTrace();
			
			Log.e(TAG, e.toString());
		}
	}

	public void loadGrammar(File grammar) throws IOException {
		mGrammar = grammar;
		mSrecGrammar = mSrec.new Grammar(mGrammar.getPath());
		mSrecGrammar.setupRecognizer();
	}

	public void setListener(GrammarListener listener) {
		mListener = listener;
	}

	public void recognize() {
		interrupt();

		mThread = new Thread() {
			public void run() {
				recognizeThread();
			}
		};
		mThread.start();
	}

	public void interrupt() {
		if (mThread != null)
			mThread.interrupt();
		mThread = null;
	}

	public void compileGrammar(GrammarMap grammar, File base, File output)
	    throws IOException {
		if (Config.DEBUG)
			Log.i(TAG, "Compiling " + base.getName() + " to " + output.getName());
		if (!base.exists() || !base.canRead())
			throw new IOException("Cannot read base grammar");
		Grammar srecGrammar = mSrec.new Grammar(base.getPath());
		srecGrammar.setupRecognizer();
		srecGrammar.resetAllSlots();
		addWordsToGrammar(srecGrammar, grammar);
		if (Config.DEBUG)
			Log.i(TAG, "Attempting to compile with new words");
		srecGrammar.compile();
		output.getParentFile().mkdirs();
		srecGrammar.save(output.getPath());
		srecGrammar.destroy();
	}

	private static void addWordsToGrammar(Grammar destination, GrammarMap grammar) {
		for (String slot : grammar.getSlots()) {
			List<GrammarEntry> entries = grammar.getSlot(slot);
			for (GrammarEntry entry : entries) {
				destination.addWordToSlot(slot, entry.word, entry.pron, entry.weight,
				    entry.tag);
			}
		}
	}

	private void recognizeThread() {
		if (Config.DEBUG)
			Log.e(TAG, "Started recognition thread");
		
		InputStream mic = null;
		boolean recognizerStarted = false;

		try {
			mic = new MicrophoneInputStream(SAMPLE_RATE, SAMPLE_RATE * 15);

			mSrec.start();
			recognizerStarted = true;

			while (recognizerStarted) {
				if (Thread.interrupted())
					throw new InterruptedException();
				int event = mSrec.advance();
				switch (event) {
					case Recognizer.EVENT_INVALID:
						recognizerStarted = false;
						break;
					case Recognizer.EVENT_INCOMPLETE:
					case Recognizer.EVENT_STARTED:
					case Recognizer.EVENT_START_OF_VOICING:
					case Recognizer.EVENT_END_OF_VOICING:
						continue;
					case Recognizer.EVENT_RECOGNITION_RESULT:
						onRecognitionSuccess();
						break;
					case Recognizer.EVENT_NEED_MORE_AUDIO:
						mSrec.putAudio(mic);
						continue;
					case Recognizer.EVENT_NO_MATCH:
					  mListener.onRecognitionFailure();
					default:
						if (mListener != null)
							mListener.onRecognitionError(Recognizer.eventToString(event));
				}
			}
		} catch (IOException e) {
			mListener.onRecognitionError(e.toString());
		} catch (InterruptedException e) {
			// do nothing
		} catch (IllegalStateException e) {
			Log.e(TAG, e.toString());
	  } finally {
			try {
				if (mic != null)
					mic.close();
			} catch (IOException ex) {
			}
			mic = null;

			try {
				if (mSrec != null && recognizerStarted)
					mSrec.stop();
			} catch (IllegalStateException e) {
				Log.e(TAG, e.toString());
		  }
		}
	}

	private void onRecognitionSuccess() throws InterruptedException {
		ArrayList<Bundle> results = new ArrayList<Bundle>();

		for (int index = 0; index < mSrec.getResultCount() && index < RESULT_LIMIT; index++) {
			String confidence = mSrec.getResult(index, Recognizer.KEY_CONFIDENCE);
			String literal = mSrec.getResult(index, Recognizer.KEY_LITERAL);
			String meaning = mSrec.getResult(index, Recognizer.KEY_MEANING);

			Bundle result = new Bundle();
			result.putString(KEY_CONFIDENCE, confidence);
			result.putString(KEY_LITERAL, literal);
			result.putString(KEY_MEANING, meaning);

			results.add(result);
		}

		if (Thread.interrupted())
			throw new InterruptedException();

		if (mListener != null)
			mListener.onRecognitionSuccess(results);
	}

	public static interface GrammarListener {
		public void onRecognitionSuccess(ArrayList<Bundle> results);

    public void onRecognitionFailure();

		public void onRecognitionError(String reason);
	}

	public static class GrammarMap {
		Map<String, LinkedList<GrammarEntry>> slotMap;

		public GrammarMap() {
			slotMap = new HashMap<String, LinkedList<GrammarEntry>>();
		}

		public Set<String> getSlots() {
			return slotMap.keySet();
		}

		public List<GrammarEntry> getSlot(String slot) {
			return slotMap.get(slot);
		}

		public void addWord(String slot, String word, String pron, int weight,
		    String tag) {
			LinkedList<GrammarEntry> entries = slotMap.get(slot);

			if (entries == null) {
				entries = new LinkedList<GrammarEntry>();
				slotMap.put(slot, entries);
			}

			GrammarEntry entry = new GrammarEntry();
			entry.word = word;
			entry.pron = pron;
			entry.weight = weight;
			entry.tag = tag;

			entries.add(entry);
		}
	}

	private static class GrammarEntry {
		String word;
		String pron;
		int weight;
		String tag;
	}
}
