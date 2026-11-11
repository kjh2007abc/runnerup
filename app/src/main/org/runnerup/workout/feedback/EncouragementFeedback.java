/*
 * 放到 app/src/main/org/runnerup/workout/feedback/EncouragementFeedback.java
 *
 * 鼓励反馈：播放预生成的音频片段，而不是走 TTS。
 * 没装音色包时自动退回 TTS，这样开发阶段不用等音频就能跑。
 *
 * 用法（在 WorkoutBuilder 里，照现有 CoachFeedback 的写法）：
 *     TargetTrigger tr = new TargetTrigger(...);
 *     tr.triggerAction.add(new EncouragementFeedback(CueType.PACE_DROP));
 *
 * GPL-3.0（跟随 RunnerUp 主项目）
 */
package org.runnerup.workout.feedback;

import android.content.Context;
import java.util.HashMap;
import org.runnerup.workout.Feedback;
import org.runnerup.workout.Workout;

public class EncouragementFeedback extends Feedback {

  /** 触发场景。音频文件按这个命名，话术库也按这个分类。 */
  public enum CueType {
    PACE_DROP,      // 配速连续下降
    PACE_RECOVER,   // 配速拉回来了
    IN_ZONE,        // 心率/配速进入目标区间
    UPHILL,         // 爬坡
    MILESTONE,      // 整公里数
    FINAL_PUSH,     // 到达目标 80%，冲刺动员
    PLATEAU,        // 长时间匀速，乏味期
    PERSONAL_BEST,  // 超过历史最佳
    START,          // 起跑
    FINISH          // 结束
  }

  private final CueType cueType;
  private RUTextToSpeech textToSpeech;
  private EncouragementPlayer player;

  public EncouragementFeedback(CueType cueType) {
    this.cueType = cueType;
  }

  @Override
  public void onBind(Workout s, HashMap<String, Object> bindValues) {
    super.onBind(s, bindValues);
    // 拿 TTS 做兜底：音色包没装或缺这一条时用
    if (bindValues.containsKey(Workout.KEY_TTS)) {
      textToSpeech = (RUTextToSpeech) bindValues.get(Workout.KEY_TTS);
    }
  }

  @Override
  public void onStart(Workout s) {
    super.onStart(s);
    // 播放器在一次跑步开始时建，结束时放掉，避免常驻占资源
  }

  @Override
  public void onEnd(Workout s) {
    super.onEnd(s);
    if (player != null) {
      player.release();
      player = null;
    }
  }

  @Override
  public boolean equals(Feedback other) {
    if (!(other instanceof EncouragementFeedback o)) return false;
    return this.cueType == o.cueType;
  }

  @Override
  public void emit(Workout w, Context ctx) {
    if (player == null) {
      player = new EncouragementPlayer(ctx);
    }

    // 1. 先试音色包
    if (player.play(cueType)) {
      return;
    }

    // 2. 包里没有这一条 → 退回 TTS，开发期全靠它
    if (textToSpeech != null) {
      String fallback = fallbackText(cueType);
      if (fallback != null) {
        textToSpeech.speak(fallback, UtterancePrio.PRIO_CUE, /* flush= */ false, null);
      }
    }
  }

  /** 开发期占位文案。正式版本靠音色包，这里只保证"有声音"。 */
  private String fallbackText(CueType type) {
    return switch (type) {
      case PACE_DROP -> "配速掉了，拉回来";
      case PACE_RECOVER -> "回来了，保持住";
      case IN_ZONE -> "就是这个节奏";
      case UPHILL -> "这个坡，别停";
      case MILESTONE -> "继续";
      case FINAL_PUSH -> "最后一段，全给我";
      case PLATEAU -> "稳住，你还有";
      case PERSONAL_BEST -> "破纪录了";
      case START -> "开始吧";
      case FINISH -> "完成";
    };
  }
}
