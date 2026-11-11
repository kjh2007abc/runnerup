/*
 * 放到 app/src/main/org/runnerup/workout/feedback/EncouragementPlayer.java
 *
 * 音色包播放器。做三件事：
 *   1. 找到当前选中的「风格 × 音色」包
 *   2. 同一场景有多条备选时随机挑，且不连着重复
 *   3. 抢音频焦点 —— 跑步的人在听音乐，不抢焦点鼓励会被盖住
 *
 * 音频放这个结构：
 *   assets/encouragement/<style>/<voice>/<CUE_TYPE>_<n>.mp3
 *   例：assets/encouragement/commentator/male_young/FINAL_PUSH_03.mp3
 *
 * 注：音频焦点的版本分支照抄 RUTextToSpeech 的写法（minSdk 24，
 *     AudioFocusRequest 要 API 26，低版本走 deprecated 的老接口）。
 *
 * GPL-3.0（跟随 RunnerUp 主项目）
 */
package org.runnerup.workout.feedback;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class EncouragementPlayer {

  private static final String TAG = "EncouragementPlayer";
  private static final String BASE_DIR = "encouragement";

  public static final String PREF_STYLE = "encouragement_style";
  public static final String PREF_VOICE = "encouragement_voice";
  private static final String DEFAULT_STYLE = "commentator";
  private static final String DEFAULT_VOICE = "male_young";

  private final Context ctx;
  private final AudioManager audioManager;
  private final String packPath;
  private final Random random = new Random();

  /** 每个场景上一次播的是哪一条，用来避免连着重复 */
  private final Map<EncouragementFeedback.CueType, String> lastPlayed = new HashMap<>();

  private MediaPlayer mediaPlayer;
  private AudioFocusRequest audioFocusRequest;
  private boolean hasAudioFocus = false;

  public EncouragementPlayer(Context context) {
    this.ctx = context.getApplicationContext();
    this.audioManager = ContextCompat.getSystemService(this.ctx, AudioManager.class);

    var prefs = PreferenceManager.getDefaultSharedPreferences(this.ctx);
    String style = prefs.getString(PREF_STYLE, DEFAULT_STYLE);
    String voice = prefs.getString(PREF_VOICE, DEFAULT_VOICE);
    this.packPath = BASE_DIR + "/" + style + "/" + voice;
  }

  /**
   * 播一条。
   *
   * @return true 已开始播放；false 包里没有这个场景，调用方该走 TTS 兜底
   */
  public boolean play(EncouragementFeedback.CueType cueType) {
    String file = pickClip(cueType);
    if (file == null) return false;

    try {
      stopCurrent();

      AssetFileDescriptor afd = ctx.getAssets().openFd(packPath + "/" + file);

      mediaPlayer = new MediaPlayer();
      mediaPlayer.setAudioAttributes(buildAttributes());
      mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
      afd.close();

      mediaPlayer.setOnCompletionListener(
          mp -> {
            abandonFocus();
            mp.release();
            if (mediaPlayer == mp) mediaPlayer = null;
          });
      mediaPlayer.setOnErrorListener(
          (mp, what, extra) -> {
            Log.w(TAG, "播放出错 what=" + what + " extra=" + extra);
            abandonFocus();
            return false;
          });

      requestFocus();
      mediaPlayer.prepare();
      mediaPlayer.start();
      lastPlayed.put(cueType, file);
      return true;

    } catch (IOException e) {
      // 包里没这个场景的音频，很正常 —— 交给 TTS 兜底
      Log.d(TAG, "包里没有 " + cueType + ": " + e.getMessage());
      return false;
    } catch (Exception e) {
      Log.w(TAG, "播放失败", e);
      abandonFocus();
      return false;
    }
  }

  /** 列出该场景的所有备选，随机挑一条，尽量不跟上次重复 */
  private String pickClip(EncouragementFeedback.CueType cueType) {
    try {
      String[] all = ctx.getAssets().list(packPath);
      if (all == null || all.length == 0) return null;

      String prefix = cueType.name() + "_";
      List<String> candidates = new ArrayList<>();
      for (String f : all) {
        if (f.startsWith(prefix)) candidates.add(f);
      }
      if (candidates.isEmpty()) return null;
      if (candidates.size() == 1) return candidates.get(0);

      String last = lastPlayed.get(cueType);
      if (last != null) candidates.remove(last);

      return candidates.get(random.nextInt(candidates.size()));

    } catch (IOException e) {
      Log.w(TAG, "列目录失败: " + packPath, e);
      return null;
    }
  }

  private AudioAttributes buildAttributes() {
    // ASSISTANCE_NAVIGATION_GUIDANCE：系统按"导航播报"处理，
    // 把音乐压低而不是暂停，跑步时体验好很多
    return new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build();
  }

  private void requestFocus() {
    if (audioManager == null || hasAudioFocus) return;

    int result;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      audioFocusRequest =
          new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
              .setAudioAttributes(buildAttributes())
              .build();
      result = audioManager.requestAudioFocus(audioFocusRequest);
    } else {
      result = requestLegacyFocus();
    }
    hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
  }

  @SuppressWarnings("deprecation")
  private int requestLegacyFocus() {
    return audioManager.requestAudioFocus(
        null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
  }

  private void abandonFocus() {
    if (audioManager == null || !hasAudioFocus) return;
    hasAudioFocus = false;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (audioFocusRequest != null) {
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
        audioFocusRequest = null;
      }
    } else {
      abandonLegacyFocus();
    }
  }

  @SuppressWarnings("deprecation")
  private void abandonLegacyFocus() {
    audioManager.abandonAudioFocus(null);
  }

  private void stopCurrent() {
    if (mediaPlayer != null) {
      try {
        if (mediaPlayer.isPlaying()) mediaPlayer.stop();
        mediaPlayer.release();
      } catch (Exception ignored) {
        // 已经 release 过，忽略
      }
      mediaPlayer = null;
    }
  }

  public void release() {
    stopCurrent();
    abandonFocus();
  }
}
