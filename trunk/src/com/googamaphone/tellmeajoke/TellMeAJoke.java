package com.googamaphone.tellmeajoke;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Random;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog.Builder;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnClickListener;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.Engine;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.speech.tts.TextToSpeech.OnUtteranceCompletedListener;
import android.util.Config;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.googamaphone.recognizer.GrammarRecognizer;
import com.googamaphone.recognizer.GrammarRecognizer.GrammarListener;
import com.googamaphone.recognizer.GrammarRecognizer.GrammarMap;

public class TellMeAJoke extends Activity implements GrammarListener
{
  private static final String TAG = "TellMeAJoke";
  private static final String EXTENSION = ".g2g";

  private static String BASE_GRAMMAR = "base";
  private static String JOKE_GRAMMAR = "comp";

  private static final int REQUEST_CHECK_DATA = 1;
  private static final int REQUEST_INSTALL_DATA = 2;

  private static final int DIALOG_INSTALL_DATA = 1;

  private static final String PROMPT_RESPONSE = "response";
  private static final String PROMPT_NO_RESPONSE = "noresponse";

  private static final String NS_JOKE = null;
  private static final String TAG_KNOCK = "knockknock";
  private static final String ATTR_WHOSTHERE = "whosthere";
  private static final String ATTR_RESPONSE = "response";

  private static final String MEANING_JOKE = "joke";
  private static final String MEANING_WHOSTHERE = "whosthere";
  private static final String MEANING_KNOCK = "knock";
  private static final String MEANING_WHO = "who";
  private static final String MEANING_WHAT = "what";

  private static final int MESSAGE_TOAST = 1;
  private static final int MESSAGE_LOADED = 2;
  private static final int RECOGNITION_START = 3;
  private static final int RECOGNITION_DONE = 4;
  private static final int SPEAKING_START = 5;
  private static final int SPEAKING_DONE = 6;

  private String baseFile;
  private String jokeFile;

  private TextToSpeech tts;
  private GrammarRecognizer recognizer;
  private HashMap<String, String> params;
  private ArrayList<KnockKnockJoke> jokeList;
  private KnockKnockJoke current;
  private Random rand;

  // private Button recognize;
  // private Button interrupt;
  private TextView loadingData;
  private TextView statusText;
  private ProgressBar loadingIcon;
  private ImageView speakerIcon;

  private final Handler handler = new Handler() {
    @Override
    public void handleMessage(Message msg) {
      switch (msg.what) {
        case MESSAGE_TOAST:
          Toast.makeText(TellMeAJoke.this, (String) msg.obj, 3000).show();
          break;
        case MESSAGE_LOADED:
          statusText.setVisibility(View.VISIBLE);
          speakerIcon.setVisibility(View.VISIBLE);

          loadingData.setVisibility(View.GONE);
          loadingIcon.setVisibility(View.GONE);
          break;
        case SPEAKING_START:
          statusText.setText(msg.obj.toString());

          speakerIcon.setImageResource(R.drawable.speaker);
          speakerIcon.setBackgroundResource(R.anim.speaker);
          AnimationDrawable anim = (AnimationDrawable) speakerIcon
              .getBackground();
          anim.start();
          break;
        case SPEAKING_DONE:
          speakerIcon.setImageResource(R.drawable.speaker);
          speakerIcon.setBackgroundDrawable(null);
          break;
        case RECOGNITION_START:
          speakerIcon.setImageResource(R.drawable.microphone);
          // recognize.setEnabled(false);
          // interrupt.setEnabled(true);
          break;
        case RECOGNITION_DONE:
          speakerIcon.setImageResource(R.drawable.speaker);
          // recognize.setEnabled(true);
          // interrupt.setEnabled(false);
          break;
      }
    }
  };

  private final OnUtteranceCompletedListener utteranceListener = new OnUtteranceCompletedListener() {
    @Override
    public void onUtteranceCompleted(String utteranceId) {
      handler.sendEmptyMessage(SPEAKING_DONE);

      if (Config.DEBUG)
        Log.i("TellMeAJoke", "utteranceCompleted");

      // if (PROMPT_RESPONSE.equals(utteranceId)) {
      handler.sendEmptyMessage(RECOGNITION_START);
      recognizer.recognize();
      // }
    }
  };

  private final OnInitListener initListener = new OnInitListener() {
    @Override
    public void onInit(int status) {
      onTtsInit(status);
    }
  };

  /** Called when the activity is first created. */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);

    // recognize = (Button) findViewById(R.id.recognize);
    // interrupt = (Button) findViewById(R.id.interrupt);
    loadingData = (TextView) findViewById(R.id.loadingData);
    statusText = (TextView) findViewById(R.id.status);
    loadingIcon = (ProgressBar) findViewById(R.id.loadingIcon);
    speakerIcon = (ImageView) findViewById(R.id.speaker);

    rand = new Random(System.currentTimeMillis());
    params = new HashMap<String, String>();
    jokeList = new ArrayList<KnockKnockJoke>();
    current = null;

    int version = 0;

    try {
      version = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
    } catch (NameNotFoundException e) {
      Log.e(TAG, "Couldn't get version information, assuming 0");
    }

    baseFile = BASE_GRAMMAR + "." + version + EXTENSION;
    jokeFile = JOKE_GRAMMAR + "." + version + EXTENSION;

    if (!prepareJokes()) {
      Toast.makeText(this, R.string.prep_jokes_failed, 3000).show();
      // recognize.setEnabled(false);

      return;
    }

    if (!prepareRecognizer()) {
      Toast.makeText(this, R.string.prep_recog_failed, 3000).show();
      // recognize.setEnabled(false);

      return;
    }

    tts = new TextToSpeech(this, initListener);
  }

  private boolean prepareJokes() {
    try {
      XmlResourceParser parser = getResources().getXml(R.xml.jokes);

      int eventType = parser.getEventType();
      while (eventType != XmlResourceParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG) {
          if (TAG_KNOCK.equalsIgnoreCase(parser.getName())) {
            KnockKnockJoke joke = new KnockKnockJoke();
            joke.whosthere = parser.getAttributeValue(NS_JOKE, ATTR_WHOSTHERE);
            joke.response = parser.getAttributeValue(NS_JOKE, ATTR_RESPONSE);
            jokeList.add(joke);

            if (Config.DEBUG)
              Log.i(TAG, "Added '" + joke.whosthere + "'");
          }
        }

        eventType = parser.next();
      }

      parser.close();

      if (jokeList.isEmpty())
        return false;

      return true;
    } catch (XmlPullParserException e) {
      Log.e(TAG, e.toString());

      return false;
    } catch (IOException e) {
      Log.e(TAG, e.toString());

      return false;
    }
  }

  private void onTtsInit(int status) {
    switch (status) {
      case TextToSpeech.SUCCESS:
        Intent intent = new Intent(Engine.ACTION_CHECK_TTS_DATA);
        startActivityForResult(intent, REQUEST_CHECK_DATA);
        break;
      default:
        String txt = getResources().getString(R.string.prep_synth_failed);
        handler.obtainMessage(MESSAGE_TOAST, txt).sendToTarget();
    }
  }

  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    switch (requestCode) {
      case REQUEST_CHECK_DATA:
        onTtsCheck(resultCode, data);
        break;
      case REQUEST_INSTALL_DATA:
        onTtsInit(TextToSpeech.SUCCESS);
        break;
    }
  }

  private void onTtsCheck(int resultCode, Intent data) {
    switch (resultCode) {
      case Engine.CHECK_VOICE_DATA_PASS: {
        tts.setLanguage(Locale.UK);
        tts.setOnUtteranceCompletedListener(utteranceListener);
        handler.sendEmptyMessageDelayed(MESSAGE_LOADED, 3000);
        speakPrompt("Say, tell me a joke", PROMPT_RESPONSE);
        break;
      }

      default: {
        showDialog(DIALOG_INSTALL_DATA);
        break;
      }
    }
  }

  @Override
  protected Dialog onCreateDialog(int id) {
    switch (id) {
      case DIALOG_INSTALL_DATA: {
        OnClickListener onClick = new OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            switch (which) {
              case DialogInterface.BUTTON_POSITIVE: {
                Intent intent = new Intent(Engine.ACTION_INSTALL_TTS_DATA);
                startActivityForResult(intent, REQUEST_INSTALL_DATA);
                break;
              }
            }
          }
        };

        return new Builder(this).setMessage(R.string.alert_install_data)
            .setTitle(R.string.title_install_data).setPositiveButton(
                android.R.string.ok, onClick).setNegativeButton(
                android.R.string.no, onClick).create();
      }
    }

    return null;
  }

  @Override
  public void onStop() {
    tts.stop();
    tts.shutdown();
    recognizer.shutdown();

    super.onStop();
  }

  private boolean prepareRecognizer() {
    File baseGrammar = getFileStreamPath(baseFile);

    if (!baseGrammar.exists()) {
      deleteGrammarFiles();

      if (Config.DEBUG)
        Log.i(TAG, "Extracting base grammar");

      try {
        baseGrammar.getParentFile().mkdirs();

        InputStream in = getResources().openRawResource(R.raw.tellmeajoke);
        FileOutputStream out = new FileOutputStream(baseGrammar);

        byte[] buffer = new byte[1024];
        int count = 0;

        while ((count = in.read(buffer)) > 0) {
          out.write(buffer, 0, count);
        }
      } catch (IOException e) {
        return false;
      }
    }

    File jokeGrammar = getFileStreamPath(jokeFile);
    GrammarMap grammar = new GrammarMap();

    for (KnockKnockJoke joke : jokeList) {
      grammar.addWord("@Knocks", joke.whosthere, null, 1, "V='"
          + joke.whosthere.toLowerCase() + "'");
    }

    grammar.addWord("@Answers", "what", null, 1, "V='what'");
    grammar.addWord("@Answers", "again", null, 1, "V='what'");
    grammar.addWord("@Answers", "say that again", null, 1, "V='what'");
    grammar.addWord("@Answers", "repeat that", null, 1, "V='what'");

    try {
      recognizer = new GrammarRecognizer(this);
      recognizer.setListener(this);

      if (!jokeGrammar.exists()) {
        recognizer.compileGrammar(grammar, baseGrammar, jokeGrammar);
      }

      recognizer.loadGrammar(jokeGrammar);
    } catch (IOException e) {
      Log.e(TAG, e.toString());
      return false;
    }

    return true;
  }

  private void speakPrompt(String prompt, String response) {
    handler.obtainMessage(SPEAKING_START, prompt).sendToTarget();

    if (response != null) {
      params.put(Engine.KEY_PARAM_UTTERANCE_ID, response);
    } else {
      params.remove(Engine.KEY_PARAM_UTTERANCE_ID);
    }

    tts.speak(prompt, TextToSpeech.QUEUE_FLUSH, params);
  }

  public void onButtonPress(View v) {
    if (Config.DEBUG)
      Log.i(TAG, "Button pressed!");

    recognizer.recognize();
    handler.sendEmptyMessage(RECOGNITION_START);
  }

  public void onInterruptPress(View v) {
    if (Config.DEBUG)
      Log.i(TAG, "Interrupt pressed!");

    recognizer.interrupt();
    handler.sendEmptyMessage(RECOGNITION_DONE);
  }

  @Override
  public void onRecognitionError(String reason) {
    Log.e(TAG, "Error: " + reason);

    String obj = "Recognition encountered an error: " + reason;
    handler.obtainMessage(MESSAGE_TOAST, obj).sendToTarget();
  }

  @Override
  public void onRecognitionFailure() {
    String expected = current.whosthere + " " + MEANING_WHO;
    Log.e(TAG, "Expected '" + expected + "'");
    switch (rand.nextInt(3)) {
      case 0:
        speakPrompt("Sorry, I'm not sure what you said.", PROMPT_RESPONSE);
        break;
      case 1:
        speakPrompt("Could you repeat that?", PROMPT_RESPONSE);
        break;
      case 2:
        speakPrompt(
            "What was that? Try saying, " + current.whosthere + " who?",
            PROMPT_RESPONSE);
        break;
    }
  }

  @Override
  public void onRecognitionSuccess(ArrayList<Bundle> results) {
    handler.sendEmptyMessage(RECOGNITION_DONE);

    if (Config.DEBUG)
      Log.i(TAG, "Received " + results.size() + " recognition results");

    boolean satisfied = false;

    for (Bundle result : results) {
      String meaning = result.getString(GrammarRecognizer.KEY_MEANING);
      String literal = result.getString(GrammarRecognizer.KEY_LITERAL);
      String confidence = result.getString(GrammarRecognizer.KEY_CONFIDENCE);

      if (Config.DEBUG)
        Log.i(TAG, "Recognized meaning:" + meaning + ", literal:" + literal
            + ", confidence:" + confidence);

      if (MEANING_JOKE.equalsIgnoreCase(meaning)) {
        current = jokeList.get(rand.nextInt(jokeList.size()));
        speakPrompt("Knock knock", PROMPT_RESPONSE);
      } else if (MEANING_KNOCK.equalsIgnoreCase(meaning)) {
        speakPrompt("I'm supposed to say that. You say, Tell me a joke.",
            PROMPT_RESPONSE);
      } else if (current != null && MEANING_WHOSTHERE.equalsIgnoreCase(meaning)) {
        speakPrompt(current.whosthere, PROMPT_RESPONSE);
      } else if (current != null && MEANING_WHAT.equalsIgnoreCase(meaning)) {
        speakPrompt(current.whosthere, PROMPT_RESPONSE);
      } else if (current != null
          && (current.whosthere + " " + MEANING_WHO).equalsIgnoreCase(meaning)) {
        speakPrompt(current.response, PROMPT_NO_RESPONSE);
        current = null;
      } else if (current != null) {
        Log.i(TAG, "Sounded like '" + meaning + "'");
        continue;
      } else {
        Log.e(TAG, "Didn't recognize '" + meaning + "'");
        switch (rand.nextInt(3)) {
          case 0:
            speakPrompt("What? Just say, tell me a joke.", PROMPT_RESPONSE);
            break;
          case 1:
            speakPrompt("Please say, tell me a joke.", PROMPT_RESPONSE);
            break;
          case 2:
            speakPrompt("Say, tell me a joke.", PROMPT_RESPONSE);
            break;
        }
      }

      satisfied = true;
      break;
    }

    if (!satisfied)
      onRecognitionFailure();
  }

  private void deleteGrammarFiles() {
    FileFilter ff = new FileFilter() {
      public boolean accept(File f) {
        String name = f.getName();
        return name.endsWith(EXTENSION);
      }
    };

    File[] files = getFilesDir().listFiles(ff);
    if (files != null) {
      for (File file : files) {
        if (Config.DEBUG)
          Log.d(TAG, "Deleted " + file);
        file.delete();
      }
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    menu.add(Menu.NONE, OPTION_CONTACT, Menu.NONE, R.string.contact_dev)
        .setIcon(android.R.drawable.ic_menu_send).setAlphabeticShortcut('c');

    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case OPTION_CONTACT:
        contactDeveloper();
        return true;
    }

    return super.onOptionsItemSelected(item);
  }

  private static final int OPTION_CONTACT = 100;

  private void contactDeveloper() {
    String appVersion = "unknown";
    String appPackage = "unknown";
    String phoneModel = android.os.Build.MODEL;
    String osVersion = android.os.Build.VERSION.RELEASE;

    try {
      PackageManager pm = getPackageManager();
      PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);
      appVersion = pi.versionName;
      appPackage = pi.packageName;
    } catch (NameNotFoundException e) {
      e.printStackTrace();
    }

    String appName = getString(R.string.app_name);
    String contactDev = getString(R.string.contact_dev);
    String contactEmail = getString(R.string.contact_email);
    String subject = getString(R.string.contact_subject, appName);
    String body = getString(R.string.contact_body, appName, appPackage,
        appVersion, phoneModel, osVersion);

    Intent sendIntent = new Intent(Intent.ACTION_SEND);
    sendIntent.putExtra(Intent.EXTRA_EMAIL, new String[] { contactEmail });
    sendIntent.putExtra(Intent.EXTRA_TEXT, body);
    sendIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
    sendIntent.setType("plain/text");

    startActivity(Intent.createChooser(sendIntent, contactDev));
  }
}
