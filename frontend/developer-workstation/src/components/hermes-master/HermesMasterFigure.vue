<template>
  <!--
    Hermes Master 的矢量骨骼：按吉祥物原图重绘，头 / 双臂 / 双腿 / logo 身体各自成组，
    姿态全部由 data-pose 驱动的 CSS 动画完成。绘制顺序决定遮挡：腿、头、手臂都在身体后面，
    "缩回 logo" 就是把它们滑到身体背后。
  -->
  <svg
    class="hm-figure"
    :data-pose="pose"
    :data-eyes="face.eyes"
    :data-mouth="face.mouth"
    :style="pose === 'spy' ? { '--hm-spy-tilt': `${spy.headTilt}deg` } : undefined"
    :class="{ 'is-airborne': airborne }"
    viewBox="0 0 120 98"
    aria-hidden="true"
  >
    <ellipse
      class="hm-shadow"
      cx="60"
      cy="95.6"
      rx="27"
      ry="2"
    />

    <g class="hm-rig">
      <g class="hm-leg hm-leg--l">
        <path
          class="hm-limb"
          d="M51.5 66 L52.4 90"
        />
        <ellipse
          class="hm-paw"
          cx="45.6"
          cy="91.6"
          rx="7.4"
          ry="3.7"
        />
      </g>
      <g class="hm-leg hm-leg--r">
        <path
          class="hm-limb"
          d="M68.5 66 L67.6 90"
        />
        <ellipse
          class="hm-paw"
          cx="74.4"
          cy="91.6"
          rx="7.4"
          ry="3.7"
        />
      </g>

      <g class="hm-upper">
        <g class="hm-head">
          <path
            class="hm-skin"
            d="M41.5 30 V22.5 C41.5 11 49.5 5.5 59.5 5.5 S77.5 11 77.5 22.5 V30 Z"
          />
          <g class="hm-face">
            <g class="hm-eyes hm-eyes--open">
              <ellipse
                class="hm-eye"
                cx="52.2"
                cy="18"
                rx="1.9"
                ry="2.3"
              />
              <ellipse
                class="hm-eye"
                cx="66.8"
                cy="18"
                rx="1.9"
                ry="2.3"
              />
            </g>
            <g class="hm-eyes hm-eyes--closed">
              <path
                class="hm-line"
                d="M49.6 18 q2.6 2.2 5.2 0"
              />
              <path
                class="hm-line"
                d="M64.2 18 q2.6 2.2 5.2 0"
              />
            </g>
            <g class="hm-eyes hm-eyes--happy">
              <path
                class="hm-line"
                d="M49.6 19.2 q2.6 -3.4 5.2 0"
              />
              <path
                class="hm-line"
                d="M64.2 19.2 q2.6 -3.4 5.2 0"
              />
            </g>
            <g class="hm-eyes hm-eyes--dizzy">
              <path
                class="hm-line hm-spiral hm-spiral--l"
                d="M52.2 18 a0.9 0.9 0 1 1 1 -0.8 a1.9 1.9 0 1 1 -2.4 1.6 a2.9 2.9 0 1 1 3.9 -2.4"
              />
              <path
                class="hm-line hm-spiral hm-spiral--r"
                d="M66.8 18 a0.9 0.9 0 1 1 1 -0.8 a1.9 1.9 0 1 1 -2.4 1.6 a2.9 2.9 0 1 1 3.9 -2.4"
              />
            </g>
            <!-- 睡着被划过时：只睁一只眼偷看 -->
            <g class="hm-eyes hm-eyes--wink">
              <path
                class="hm-line"
                d="M49.6 18 q2.6 2.2 5.2 0"
              />
              <ellipse
                class="hm-eye"
                cx="66.8"
                cy="18"
                rx="1.9"
                ry="2.3"
              />
            </g>

            <path
              class="hm-line hm-mouth hm-mouth--smile"
              d="M56 21.4 q3.6 4.4 7.4 0"
            />
            <ellipse
              class="hm-mouth hm-mouth--o"
              cx="59.6"
              cy="23"
              rx="2"
              ry="2.4"
            />
            <path
              class="hm-line hm-mouth hm-mouth--wavy"
              d="M55.4 23 q1.4 -2 2.8 0 t2.8 0 t2.8 0"
            />
          </g>
        </g>

        <g class="hm-body">
          <polygon
            class="hm-logo-red"
            points="16.5,48.25 38,28 81,28 102.5,48.25 81,68.5 38,68.5"
          />
          <polygon
            class="hm-logo-white"
            points="38,28 59.5,48.25 38,68.5"
          />
          <polygon
            class="hm-logo-white"
            points="81,28 59.5,48.25 81,68.5"
          />
          <polygon
            class="hm-logo-outline"
            points="16.5,48.25 38,28 81,28 102.5,48.25 81,68.5 38,68.5"
          />
        </g>

        <g class="hm-arm hm-arm--l">
          <path
            class="hm-limb"
            d="M32.5 44 C29.5 49.5 27 55.5 25 61.5"
          />
          <circle
            class="hm-paw"
            cx="22.6"
            cy="66"
            r="4.8"
          />
          <path
            class="hm-line hm-line--thin"
            d="M24.6 64.4 q-0.4 2.6 1.8 2.2"
          />
        </g>
        <g class="hm-arm hm-arm--r">
          <path
            class="hm-limb"
            d="M87.5 44 C90.5 49.5 93 55.5 95 61.5"
          />
          <circle
            class="hm-paw"
            cx="97.4"
            cy="66"
            r="4.8"
          />
          <path
            class="hm-line hm-line--thin"
            d="M95.4 64.4 q0.4 2.6 -1.8 2.2"
          />
          <polygon
            class="hm-stone hm-stone--held"
            points="93.4,68.6 94.6,65 98.2,64 101.2,66.6 100.8,70.2 97.6,71.8 94.4,70.8"
          />
        </g>

        <!-- 双筒望远镜（仅 spy 姿态）：两只镜筒各绕自己那只眼睛转向鼠标，黑色线条风格；
             两只手臂直接从肩画到握点。离目标近的那只镜筒要盖在另一只上面（见 BARREL_EYES）。 -->
        <g
          v-if="pose === 'spy'"
          class="hm-spy"
        >
          <path
            class="hm-limb"
            :d="spy.armLeft"
          />
          <path
            class="hm-limb"
            :d="spy.armRight"
          />
          <path
            class="hm-spy__bridge"
            :d="spy.bridge"
          />
          <g
            v-for="(eyeX, i) in BARREL_EYES"
            :key="i"
            class="hm-spy__barrel"
            :opacity="i === 2 && !spy.leftOnTop ? 0 : 1"
            :transform="`translate(${eyeX} 18) rotate(${spy.angle})`"
          >
            <g class="hm-spy__tube">
              <rect
                class="hm-spy__shell"
                x="9.5"
                y="-4.2"
                width="13.5"
                height="8.4"
                rx="1.6"
              />
              <rect
                class="hm-spy__shell"
                x="2"
                y="-3"
                width="8.5"
                height="6"
                rx="1"
              />
              <rect
                class="hm-spy__cup"
                x="-2.6"
                y="-3.4"
                width="5.2"
                height="6.8"
                rx="1.8"
              />
              <path
                class="hm-spy__ring"
                d="M20 -4.2 V4.2"
              />
              <path
                class="hm-line hm-line--thin"
                d="M12.5 -1.8 H17.5"
              />
            </g>
          </g>
          <circle
            class="hm-paw"
            :cx="spy.gripLeft.x"
            :cy="spy.gripLeft.y"
            r="4.8"
          />
          <circle
            class="hm-paw"
            :cx="spy.gripRight.x"
            :cy="spy.gripRight.y"
            r="4.8"
          />
        </g>
      </g>
    </g>

    <polygon
      class="hm-stone hm-stone--ground"
      points="101,94.6 102.2,91 105.8,90 108.8,92.6 108.4,95.4 101.6,95.6"
    />

    <g class="hm-fx hm-fx--zzz">
      <text
        x="80"
        y="16"
      >z</text>
      <text
        x="87"
        y="9"
      >z</text>
      <text
        x="95"
        y="1"
      >Z</text>
    </g>
    <g class="hm-fx hm-fx--stars">
      <g class="hm-orbit">
        <polygon
          class="hm-star"
          points="0,-3 0.9,-0.9 3,-0.9 1.3,0.5 1.9,2.6 0,1.4 -1.9,2.6 -1.3,0.5 -3,-0.9 -0.9,-0.9"
        />
        <polygon
          class="hm-star hm-star--b"
          points="0,-3 0.9,-0.9 3,-0.9 1.3,0.5 1.9,2.6 0,1.4 -1.9,2.6 -1.3,0.5 -3,-0.9 -0.9,-0.9"
        />
        <polygon
          class="hm-star hm-star--c"
          points="0,-3 0.9,-0.9 3,-0.9 1.3,0.5 1.9,2.6 0,1.4 -1.9,2.6 -1.3,0.5 -3,-0.9 -0.9,-0.9"
        />
      </g>
    </g>
    <text
      class="hm-fx hm-fx--bang"
      x="84"
      y="12"
    >!</text>
    <g class="hm-fx hm-fx--dots">
      <circle
        cx="84"
        cy="8"
        r="1.5"
      />
      <circle
        cx="90"
        cy="5"
        r="1.9"
      />
      <circle
        cx="97"
        cy="1.5"
        r="2.4"
      />
    </g>
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { binocularsGeometry, type HmPose, type Point } from '@/utils/hermesMasterBehavior'

const props = defineProps<{
  pose: HmPose
  /** 离地（被拎着 / 下落中）：隐藏脚下的影子 */
  airborne?: boolean
  /** spy 姿态下望远镜要对准的点（viewBox 坐标） */
  aim?: Point | null
}>()

/** 还没有目标时朝左上方张望 */
const spy = computed(() => binocularsGeometry(props.aim ?? { x: -140, y: -120 }))

/**
 * 镜筒所在眼睛的 x（viewBox 单位），按绘制顺序：左、右、再一只左。
 * 第三只只在目标位于左侧时显示，用来把左镜筒盖到右镜筒上面——
 * 不用重排 DOM 的办法换层级，是因为节点一移动 CSS 动画就会重播（镜筒会缩回去再抽出来一次）。
 */
const BARREL_EYES = [52.2, 66.8, 52.2]

type Eyes = 'open' | 'closed' | 'happy' | 'dizzy' | 'wide' | 'wink' | 'none'
type Mouth = 'smile' | 'o' | 'wavy' | 'talk'

const FACES: Partial<Record<HmPose, { eyes: Eyes; mouth: Mouth }>> = {
  sleep: { eyes: 'closed', mouth: 'smile' },
  peek: { eyes: 'wink', mouth: 'smile' },
  wave: { eyes: 'happy', mouth: 'smile' },
  giggle: { eyes: 'happy', mouth: 'o' },
  jump: { eyes: 'happy', mouth: 'o' },
  startled: { eyes: 'wide', mouth: 'o' },
  dragged: { eyes: 'wide', mouth: 'o' },
  fall: { eyes: 'wide', mouth: 'o' },
  land: { eyes: 'closed', mouth: 'smile' },
  dizzy: { eyes: 'dizzy', mouth: 'wavy' },
  talk: { eyes: 'open', mouth: 'talk' },
  // 两只眼睛都贴在目镜眼罩后面
  spy: { eyes: 'none', mouth: 'smile' }
}

const face = computed(() => FACES[props.pose] ?? { eyes: 'open', mouth: 'smile' })
</script>

<style scoped lang="scss">
// 品牌红与 ai-tokens.scss 的 $ai-red 同值；那份 token 限定只给 components/ai 用，这里不引用。
$hm-red: #db0011;
$hm-ink: #0b0b0b;

// 骨骼关节（viewBox 用户单位）
$hip-l: 51.5px 67px;
$hip-r: 68.5px 67px;
$shoulder-l: 33px 45px;
$shoulder-r: 87px 45px;
$neck: 59.5px 29px;
$ground-center: 60px 95.5px;
// 身体下沿落到地面需要下移的距离（坐下 / 睡觉 / 缩回 logo 共用）
$squat: 25px;

.hm-figure {
  display: block;
  width: 100%;
  height: 100%;
  overflow: visible;
  // --hm-dir / --hm-look-x / --hm-look-y / --hm-swing 由 useHermesMasterBehavior 写在外层容器上，
  // 这里只用带默认值的 var() 读取；在本元素上声明初值会把继承来的值盖掉。

  * {
    transform-box: view-box;
  }
}

.hm-skin,
.hm-paw {
  fill: #fff;
  stroke: $hm-ink;
  stroke-width: 1.9;
  stroke-linejoin: round;
}

.hm-limb,
.hm-line {
  fill: none;
  stroke: $hm-ink;
  stroke-width: 2.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.hm-line {
  stroke-width: 1.5;

  &--thin {
    stroke-width: 1.1;
  }
}

.hm-eye,
.hm-mouth--o {
  fill: $hm-ink;
}

.hm-stone {
  fill: #9aa1ab;
  stroke: $hm-ink;
  stroke-width: 1.3;
  stroke-linejoin: round;
  opacity: 0;
}

.hm-logo-red {
  fill: $hm-red;
}

.hm-logo-white {
  fill: #fff;
}

.hm-logo-outline {
  fill: none;
  stroke: $hm-ink;
  stroke-width: 1.9;
  stroke-linejoin: round;
}

.hm-shadow {
  fill: rgba(0, 0, 0, 0.16);
  transform-origin: $ground-center;
  transition: opacity 0.2s, transform 0.3s;

  .is-airborne & {
    opacity: 0;
  }
}

// ---------- 静态关节：姿态切换时靠 transition 过渡 ----------
.hm-rig {
  transform-origin: $ground-center;
  transition: transform 0.35s ease;
}

.hm-upper,
.hm-head,
.hm-arm,
.hm-leg {
  transition: transform 0.4s cubic-bezier(0.34, 1.4, 0.64, 1);
}

.hm-upper {
  // 身体下沿中点：坐着 / 瘫坐时左右晃动的支点
  transform-origin: 60px 69px;
}

.hm-head {
  transform-origin: $neck;
}

.hm-arm--l {
  transform-origin: $shoulder-l;
}

.hm-arm--r {
  transform-origin: $shoulder-r;
}

.hm-leg--l {
  transform-origin: $hip-l;
}

.hm-leg--r {
  transform-origin: $hip-r;
}

// ---------- 表情 ----------
.hm-eyes,
.hm-mouth {
  opacity: 0;
}

.hm-face {
  transform: translate(calc(var(--hm-look-x, 0) * 2.2px), calc(var(--hm-look-y, 0) * 1.4px));
  transition: transform 0.18s ease-out;
}

@each $eyes in (closed, happy, dizzy, wink) {
  [data-eyes='#{$eyes}'] .hm-eyes--#{$eyes} {
    opacity: 1;
  }
}

[data-eyes='open'] .hm-eyes--open,
[data-eyes='wide'] .hm-eyes--open {
  opacity: 1;
}

[data-eyes='open'] .hm-eye {
  transform-origin: center;
  transform-box: fill-box;
  animation: hm-blink 4.6s infinite;
}

[data-eyes='wide'] .hm-eye {
  transform-origin: center;
  transform-box: fill-box;
  transform: scale(1.35);
}

[data-mouth='smile'] .hm-mouth--smile,
[data-mouth='wavy'] .hm-mouth--wavy,
[data-mouth='o'] .hm-mouth--o,
[data-mouth='talk'] .hm-mouth--o {
  opacity: 1;
}

[data-mouth='talk'] .hm-mouth--o {
  transform-origin: center;
  transform-box: fill-box;
  animation: hm-talk 0.32s ease-in-out infinite alternate;
}

.hm-spiral {
  transform-box: fill-box;
  transform-origin: center;
  animation: hm-spin 1.1s linear infinite;

  &--r {
    animation-direction: reverse;
  }
}

// ---------- 特效 ----------
.hm-fx {
  opacity: 0;
  pointer-events: none;
  font: 700 9px/1 'Inter Variable', Inter, system-ui, sans-serif;
  fill: #5b6470;
}

[data-pose='sleep'] .hm-fx--zzz,
[data-pose='peek'] .hm-fx--zzz {
  opacity: 1;

  text {
    animation: hm-zzz 2.4s ease-in-out infinite;

    &:nth-child(2) {
      animation-delay: 0.5s;
    }

    &:nth-child(3) {
      animation-delay: 1s;
      font-size: 11px;
    }
  }
}

.hm-fx--bang {
  font-size: 17px;
  fill: $hm-red;
}

[data-pose='startled'] .hm-fx--bang {
  animation: hm-pop 0.9s ease-out both;
}

.hm-fx--dots circle {
  fill: #fff;
  stroke: $hm-ink;
  stroke-width: 1;
}

[data-pose='think'] .hm-fx--dots {
  opacity: 1;

  circle {
    animation: hm-dot 1.5s ease-in-out infinite;

    &:nth-child(2) {
      animation-delay: 0.2s;
    }

    &:nth-child(3) {
      animation-delay: 0.4s;
    }
  }
}

.hm-star {
  fill: #f5b400;
  stroke: $hm-ink;
  stroke-width: 0.5;
  transform: translate(10px, 0);

  &--b {
    transform: rotate(120deg) translate(10px, 0);
  }

  &--c {
    transform: rotate(240deg) translate(10px, 0);
  }
}

// 晕倒时瘫坐在地上，星星压成椭圆轨道绕着头顶转
.hm-fx--stars {
  transform: translate(59.5px, 24px) scale(1.5, 0.5);
}

[data-pose='dizzy'] .hm-fx--stars {
  opacity: 1;

  .hm-orbit {
    animation: hm-spin 1.3s linear infinite;
  }
}

// ---------- 姿态 ----------
[data-pose='idle'] .hm-upper,
[data-pose='talk'] .hm-upper {
  animation: hm-breathe 3.2s ease-in-out infinite;
}

// 踱步：左右腿交替摆，手臂反向摆，身体朝行进方向微倾，眼睛看向前方
[data-pose='walk'] {
  .hm-rig {
    transform: rotate(calc(var(--hm-dir, 0) * 3deg));
  }

  .hm-face {
    transform: translate(calc(var(--hm-dir, 0) * 2.6px), 0);
  }

  .hm-upper {
    animation: hm-bob 0.28s ease-in-out infinite alternate;
  }

  .hm-leg--l,
  .hm-arm--r {
    animation: hm-swing-a 0.56s ease-in-out infinite;
  }

  .hm-leg--r,
  .hm-arm--l {
    animation: hm-swing-b 0.56s ease-in-out infinite;
  }
}

// 思考：右手挠头，头歪一点，眼睛往上看
[data-pose='think'] {
  .hm-head {
    transform: rotate(-7deg);
  }

  .hm-face {
    transform: translate(2px, -1.6px);
  }

  .hm-arm--r {
    transform: rotate(-172deg);
    animation: hm-scratch 0.9s ease-in-out 0.4s infinite alternate;
  }
}

// 坐下：上身落到地面，双腿向两侧伸开
[data-pose='sit'] {
  .hm-upper {
    transform: translateY(21px);
    animation: hm-sit-sway 3.6s ease-in-out 0.5s infinite;
  }

  .hm-leg--l {
    transform: translateY(21px) rotate(76deg);
  }

  .hm-leg--r {
    transform: translateY(21px) rotate(-76deg);
  }

  .hm-arm--l {
    transform: rotate(32deg);
  }

  .hm-arm--r {
    transform: rotate(-32deg);
  }

  .hm-shadow {
    transform: scaleX(1.5);
  }
}

// 睡觉：腿收到身体背后，logo 趴在地上，头歪着闭眼
[data-pose='sleep'],
[data-pose='peek'] {
  .hm-upper {
    transform: translateY($squat);
    animation: hm-snore 3.4s ease-in-out infinite;
  }

  .hm-leg {
    transform: translateY(9px) scaleY(0.05);
  }

  .hm-head {
    transform: translateY(2.5px) rotate(-11deg);
  }

  .hm-arm--l {
    transform: rotate(18deg);
  }

  .hm-arm--r {
    transform: rotate(-18deg);
  }
}

[data-pose='peek'] .hm-head {
  transform: translateY(1px) rotate(-4deg);
}

// 未激活：打开 DW 时只是右下角地上的一块 logo，被点击后才出场。
// 各关节的值就是 emerge 动画的第 0 帧，切过去不会跳。
[data-pose='dormant'] {
  .hm-upper {
    transform: translateY($squat);
  }

  .hm-head {
    transform: translateY(25px);
  }

  .hm-leg {
    transform: translateY(6px) scaleY(0.02);
  }

  .hm-arm {
    transform: scale(0.02);
  }
}

// 出场（被点击激活后）：先是地上的一块 logo → 试探着慢慢探出脑袋左右张望、缩回去一下 → 整个头出来 →
// 依次伸出双臂 → 伸腿站起（总时长与 useHermesMasterBehavior 的 EMERGE_MS 一致）
[data-pose='emerge'] {
  .hm-upper {
    animation: hm-emerge-upper 4.6s ease-in-out both;
  }

  .hm-leg {
    animation: hm-emerge-leg 4.6s ease-in-out both;
  }

  .hm-head {
    animation: hm-emerge-head 4.6s ease-in-out both;
  }

  .hm-face {
    animation: hm-emerge-look 4.6s ease-in-out both;
  }

  .hm-arm--l {
    animation: hm-emerge-arm-l 4.6s ease-out both;
  }

  .hm-arm--r {
    animation: hm-emerge-arm-r 4.6s ease-out both;
  }
}

// 双手举双筒望远镜盯着鼠标：两只常规手臂都藏起来，换成 .hm-spy 里直接画到握点的手臂
[data-pose='spy'] {
  .hm-arm {
    opacity: 0;
  }

  .hm-head {
    transform: rotate(var(--hm-spy-tilt, 0deg));
  }
}

// 与头、手、脚同一套线条：白底黑描边的镜筒，实心黑的目镜眼罩与中梁
.hm-spy__shell {
  fill: #fff;
  stroke: $hm-ink;
  stroke-width: 1.9;
  stroke-linejoin: round;
}

.hm-spy__cup {
  fill: $hm-ink;
  stroke: $hm-ink;
  stroke-width: 1.2;
  stroke-linejoin: round;
}

.hm-spy__ring {
  fill: none;
  stroke: $hm-ink;
  stroke-width: 1.9;
}

.hm-spy__bridge {
  fill: none;
  stroke: $hm-ink;
  stroke-width: 3.4;
  stroke-linecap: round;
}

// 镜筒从目镜处抽出来，之后时不时伸缩一下调焦
.hm-spy__tube {
  transform-origin: 0 0;
  animation: hm-spy-extend 0.45s cubic-bezier(0.34, 1.4, 0.64, 1) both, hm-spy-focus 2.4s ease-in-out 0.6s infinite;
}

// 彩蛋（1/1000）：发现脚边的石头 → 蹲下捡起 → 抡臂 → 朝鼠标扔出去 → 小跳收势。
// 总时长与 useHermesMasterBehavior 的 THROW_MS 一致；手里的石头在 56% 消失，
// 同一时刻（THROW_RELEASE_MS）外层把飞行的石头接上。
[data-pose='throw'] {
  .hm-upper {
    animation: hm-throw-upper 2.6s ease-in-out both;
  }

  .hm-leg {
    animation: hm-throw-leg 2.6s ease-in-out both;
  }

  .hm-arm--r {
    animation: hm-throw-arm 2.6s ease-in-out both;
  }

  .hm-arm--l {
    animation: hm-throw-balance 2.6s ease-in-out both;
  }

  .hm-face {
    animation: hm-throw-look 2.6s ease-in-out both;
  }

  .hm-stone--ground {
    animation: hm-stone-ground 2.6s linear both;
  }

  .hm-stone--held {
    animation: hm-stone-held 2.6s linear both;
  }
}

// 醒来：睁眼 → 伸懒腰站起 → 小跳一下（时长与 useHermesMasterBehavior 的 WAKE_MS 一致）
[data-pose='wake'] {
  .hm-upper {
    animation: hm-wake-upper 1.7s ease-in-out both;
  }

  .hm-leg {
    animation: hm-wake-leg 1.7s ease-in-out both;
  }

  .hm-arm--l {
    animation: hm-stretch-l 1.7s ease-in-out both;
  }

  .hm-arm--r {
    animation: hm-stretch-r 1.7s ease-in-out both;
  }
}

// 倒立：翻身用头顶地，双腿在空中蹬
[data-pose='handstand'] {
  .hm-rig {
    transform-origin: 60px 50.5px;
    animation: hm-handstand 5.2s ease-in-out both;
  }

  .hm-leg--l {
    animation: hm-kick-a 0.7s ease-in-out 0.8s 5 alternate;
  }

  .hm-leg--r {
    animation: hm-kick-b 0.7s ease-in-out 0.8s 5 alternate;
  }

  .hm-arm--l {
    transform: rotate(105deg);
  }

  .hm-arm--r {
    transform: rotate(-105deg);
  }
}

// 缩回 logo：头、四肢滑到身体背后，只剩一块 logo 落在地上，过一会儿再弹出来
[data-pose='hide'] {
  .hm-upper {
    animation: hm-hide-upper 5.6s ease-in-out both;
  }

  .hm-head {
    animation: hm-hide-head 5.6s ease-in-out both;
  }

  .hm-leg {
    animation: hm-hide-leg 5.6s ease-in-out both;
  }

  .hm-arm--l {
    animation: hm-hide-arm 5.6s ease-in-out both;
  }

  .hm-arm--r {
    animation: hm-hide-arm 5.6s ease-in-out both;
  }
}

// 挥手打招呼
[data-pose='wave'] {
  .hm-arm--r {
    animation: hm-wave 0.45s ease-in-out infinite alternate;
  }

  .hm-head {
    transform: rotate(5deg);
  }
}

// 被挠痒：全身抖
[data-pose='giggle'] {
  .hm-rig {
    animation: hm-giggle 0.16s ease-in-out infinite alternate;
  }

  .hm-arm--l {
    transform: rotate(35deg);
  }

  .hm-arm--r {
    transform: rotate(-35deg);
  }
}

// 被快速划过：吓一跳
[data-pose='startled'] {
  .hm-rig {
    animation: hm-startle 0.9s ease-out both;
  }

  .hm-arm--l {
    transform: rotate(120deg);
  }

  .hm-arm--r {
    transform: rotate(-120deg);
  }
}

// 被拎起来：以头顶为支点随拖拽速度摆动，四肢悬空乱晃
[data-pose='dragged'] {
  .hm-rig {
    transform-origin: 60px 6px;
    transform: rotate(var(--hm-swing, 0deg));
    transition: none;
  }

  .hm-leg--l {
    animation: hm-dangle-a 0.5s ease-in-out infinite alternate;
  }

  .hm-leg--r {
    animation: hm-dangle-b 0.5s ease-in-out infinite alternate;
  }

  .hm-arm--l {
    animation: hm-flail-l 0.36s ease-in-out infinite alternate;
  }

  .hm-arm--r {
    animation: hm-flail-r 0.36s ease-in-out infinite alternate;
  }
}

[data-pose='fall'] {
  .hm-arm--l {
    transform: rotate(150deg);
  }

  .hm-arm--r {
    transform: rotate(-150deg);
  }

  .hm-leg--l {
    transform: rotate(28deg);
  }

  .hm-leg--r {
    transform: rotate(-28deg);
  }
}

// 轻落地：压扁回弹
[data-pose='land'] .hm-rig {
  animation: hm-squash 0.45s ease-out both;
}

// 摔晕：先拍扁在地上，再瘫坐着晃脑袋，螺旋眼 + 头顶转星星
[data-pose='dizzy'] {
  .hm-rig {
    animation: hm-splat 0.6s ease-out both;
  }

  .hm-upper {
    transform: translateY(21px);
    animation: hm-daze 1.6s ease-in-out 0.6s infinite;
  }

  .hm-head {
    animation: hm-wobble 1.1s ease-in-out infinite;
  }

  .hm-leg--l {
    transform: translateY(21px) rotate(64deg);
  }

  .hm-leg--r {
    transform: translateY(21px) rotate(-82deg);
  }

  .hm-arm--l {
    transform: rotate(48deg);
  }

  .hm-arm--r {
    transform: rotate(-20deg);
  }

  .hm-shadow {
    transform: scaleX(1.5);
  }
}

// 从地上一跃而起（时长与 JUMP_MS 一致）
[data-pose='jump'] {
  .hm-rig {
    animation: hm-jump-up 0.9s cubic-bezier(0.3, 0.7, 0.4, 1) both;
  }

  .hm-arm--l {
    transform: rotate(150deg);
  }

  .hm-arm--r {
    transform: rotate(-150deg);
  }
}

@keyframes hm-blink {
  0%, 92%, 100% { transform: scaleY(1); }
  95% { transform: scaleY(0.1); }
}

@keyframes hm-talk {
  from { transform: scale(0.8, 0.35); }
  to { transform: scale(1.1, 1.1); }
}

@keyframes hm-spin {
  to { transform: rotate(360deg); }
}

@keyframes hm-zzz {
  0% { opacity: 0; transform: translate(-3px, 5px); }
  35% { opacity: 1; }
  100% { opacity: 0; transform: translate(3px, -5px); }
}

@keyframes hm-pop {
  0% { opacity: 0; transform: translateY(6px) scale(0.4); }
  25% { opacity: 1; transform: translateY(0) scale(1.15); }
  75% { opacity: 1; transform: translateY(0) scale(1); }
  100% { opacity: 0; transform: translateY(-4px) scale(1); }
}

@keyframes hm-dot {
  0%, 100% { opacity: 0.25; }
  50% { opacity: 1; }
}

@keyframes hm-breathe {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-0.9px); }
}

@keyframes hm-bob {
  from { transform: translateY(0); }
  to { transform: translateY(-1.6px); }
}

@keyframes hm-swing-a {
  0%, 100% { transform: rotate(17deg); }
  50% { transform: rotate(-17deg); }
}

@keyframes hm-swing-b {
  0%, 100% { transform: rotate(-17deg); }
  50% { transform: rotate(17deg); }
}

@keyframes hm-scratch {
  from { transform: rotate(-172deg); }
  to { transform: rotate(-164deg); }
}

@keyframes hm-sit-sway {
  0%, 100% { transform: translateY(21px) rotate(0); }
  50% { transform: translateY(21px) rotate(2.2deg); }
}

@keyframes hm-snore {
  0%, 100% { transform: translateY($squat) scale(1); }
  50% { transform: translateY($squat - 0.8px) scale(1.015, 1.02); }
}

@keyframes hm-emerge-upper {
  0%, 85% { transform: translateY($squat); }
  94% { transform: translateY(-5px); }
  98% { transform: translateY(1px); }
  100% { transform: translateY(0); }
}

@keyframes hm-emerge-leg {
  0%, 85% { transform: translateY(6px) scaleY(0.02); }
  94% { transform: translateY(-5px) scaleY(1); }
  98%, 100% { transform: translateY(0) scaleY(1); }
}

@keyframes hm-emerge-head {
  0%, 12% { transform: translateY(25px); }
  // 慢慢探到只露出眼睛
  32%, 46% { transform: translateY(9px); }
  // 不放心，缩回去一点
  52%, 58% { transform: translateY(16px); }
  66% { transform: translateY(-1.5px); }
  70%, 100% { transform: translateY(0); }
}

@keyframes hm-emerge-look {
  0%, 33% { transform: translate(0, 0); }
  37%, 39% { transform: translate(-2.6px, 0); }
  43%, 45% { transform: translate(2.6px, 0); }
  49%, 100% { transform: translate(0, 0); }
}

@keyframes hm-emerge-arm-l {
  0%, 70% { transform: scale(0.02); }
  76% { transform: scale(1.15); }
  80%, 100% { transform: scale(1); }
}

@keyframes hm-emerge-arm-r {
  0%, 75% { transform: scale(0.02); }
  81% { transform: scale(1.15); }
  85%, 100% { transform: scale(1); }
}

@keyframes hm-spy-extend {
  from { transform: scaleX(0.25); }
  to { transform: scaleX(1); }
}

@keyframes hm-spy-focus {
  0%, 70%, 100% { transform: scaleX(1); }
  80% { transform: scaleX(0.9); }
  90% { transform: scaleX(1.04); }
}

@keyframes hm-throw-upper {
  0%, 12% { transform: translateY(0) rotate(0); }
  24%, 32% { transform: translateY(12px) rotate(16deg); }
  44% { transform: translateY(0) rotate(-6deg); }
  52% { transform: translateY(0) rotate(-11deg); }
  58% { transform: translateY(0) rotate(11deg); }
  70% { transform: translateY(-5px) rotate(0); }
  80%, 100% { transform: translateY(0) rotate(0); }
}

@keyframes hm-throw-leg {
  0%, 12% { transform: translateY(0) scaleY(1); }
  24%, 32% { transform: translateY(12px) scaleY(0.5); }
  44%, 60% { transform: translateY(0) scaleY(1); }
  70% { transform: translateY(-5px) scaleY(0.9); }
  80%, 100% { transform: translateY(0) scaleY(1); }
}

@keyframes hm-throw-arm {
  0%, 12% { transform: rotate(0); }
  24%, 32% { transform: rotate(-8deg); }
  44% { transform: rotate(-165deg); }
  52% { transform: rotate(-208deg); }
  56% { transform: rotate(-120deg); }
  63% { transform: rotate(-35deg); }
  80%, 100% { transform: rotate(0); }
}

@keyframes hm-throw-balance {
  0%, 32% { transform: rotate(0); }
  48%, 54% { transform: rotate(48deg); }
  64% { transform: rotate(-12deg); }
  80%, 100% { transform: rotate(0); }
}

// 先低头盯着脚边的石头，捡起来之后视线回到鼠标上
@keyframes hm-throw-look {
  0% { transform: translate(calc(var(--hm-look-x, 0) * 2.2px), calc(var(--hm-look-y, 0) * 1.4px)); }
  8%, 30% { transform: translate(2.4px, 1.8px); }
  40%, 100% { transform: translate(calc(var(--hm-look-x, 0) * 2.2px), calc(var(--hm-look-y, 0) * 1.4px)); }
}

@keyframes hm-stone-ground {
  0% { opacity: 0; }
  6%, 27% { opacity: 1; }
  28%, 100% { opacity: 0; }
}

@keyframes hm-stone-held {
  0%, 27% { opacity: 0; }
  28%, 55% { opacity: 1; }
  56%, 100% { opacity: 0; }
}

@keyframes hm-wake-upper {
  0%, 22% { transform: translateY($squat); }
  62% { transform: translateY(-2px); }
  78% { transform: translateY(-7px); }
  90% { transform: translateY(1px); }
  100% { transform: translateY(0); }
}

@keyframes hm-wake-leg {
  0%, 22% { transform: translateY(9px) scaleY(0.05); }
  62% { transform: translateY(-2px) scaleY(1); }
  78% { transform: translateY(-7px) scaleY(0.9); }
  90%, 100% { transform: translateY(0) scaleY(1); }
}

@keyframes hm-stretch-l {
  0%, 25% { transform: rotate(18deg); }
  55%, 75% { transform: rotate(155deg); }
  100% { transform: rotate(0); }
}

@keyframes hm-stretch-r {
  0%, 25% { transform: rotate(-18deg); }
  55%, 75% { transform: rotate(-155deg); }
  100% { transform: rotate(0); }
}

@keyframes hm-handstand {
  0% { transform: translateY(0) rotate(0); }
  5% { transform: translateY(3px) rotate(0); }
  11% { transform: translateY(-16px) rotate(90deg); }
  16% { transform: translateY(0) rotate(180deg); }
  30% { transform: translateY(0) rotate(176deg); }
  45% { transform: translateY(0) rotate(184deg); }
  60% { transform: translateY(0) rotate(177deg); }
  75% { transform: translateY(0) rotate(183deg); }
  84% { transform: translateY(0) rotate(180deg); }
  90% { transform: translateY(-16px) rotate(270deg); }
  96% { transform: translateY(2px) rotate(360deg); }
  100% { transform: translateY(0) rotate(360deg); }
}

@keyframes hm-kick-a {
  from { transform: rotate(-24deg); }
  to { transform: rotate(20deg); }
}

@keyframes hm-kick-b {
  from { transform: rotate(24deg); }
  to { transform: rotate(-20deg); }
}

@keyframes hm-hide-upper {
  0% { transform: translateY(0); }
  8% { transform: translateY(-5px); }
  16% { transform: translateY($squat + 1.5px) scale(1.04, 0.95); }
  20%, 84% { transform: translateY($squat); }
  92% { transform: translateY(-6px); }
  100% { transform: translateY(0); }
}

@keyframes hm-hide-head {
  0% { transform: translateY(0); }
  10%, 46% { transform: translateY(25px); }
  // 中途探出眼睛张望一下
  54%, 68% { transform: translateY(9px); }
  74%, 84% { transform: translateY(25px); }
  93%, 100% { transform: translateY(0); }
}

@keyframes hm-hide-leg {
  0% { transform: translateY(0) scaleY(1); }
  12%, 84% { transform: translateY(6px) scaleY(0.02); }
  93%, 100% { transform: translateY(0) scaleY(1); }
}

@keyframes hm-hide-arm {
  0% { transform: scale(1); }
  10%, 84% { transform: scale(0.02); }
  93%, 100% { transform: scale(1); }
}

@keyframes hm-wave {
  from { transform: rotate(-128deg); }
  to { transform: rotate(-158deg); }
}

@keyframes hm-giggle {
  from { transform: rotate(-3.5deg) translateY(0); }
  to { transform: rotate(3.5deg) translateY(-1.5px); }
}

@keyframes hm-startle {
  0% { transform: translateY(0) scale(1); }
  18% { transform: translateY(-15px) scale(0.96, 1.06); }
  40% { transform: translateY(0) scale(1.06, 0.94); }
  55%, 100% { transform: translateY(0) scale(1); }
}

@keyframes hm-dangle-a {
  from { transform: rotate(-20deg); }
  to { transform: rotate(14deg); }
}

@keyframes hm-dangle-b {
  from { transform: rotate(16deg); }
  to { transform: rotate(-22deg); }
}

@keyframes hm-flail-l {
  from { transform: rotate(118deg); }
  to { transform: rotate(158deg); }
}

@keyframes hm-flail-r {
  from { transform: rotate(-158deg); }
  to { transform: rotate(-118deg); }
}

@keyframes hm-squash {
  0% { transform: scale(1.14, 0.8); }
  45% { transform: scale(0.95, 1.07); }
  100% { transform: scale(1); }
}

@keyframes hm-splat {
  0% { transform: scale(1.3, 0.45); }
  30% { transform: scale(1.22, 0.55); }
  60% { transform: scale(0.94, 1.08); }
  100% { transform: scale(1); }
}

@keyframes hm-daze {
  0%, 100% { transform: translateY(21px) rotate(-5deg); }
  50% { transform: translateY(21px) rotate(5deg); }
}

@keyframes hm-wobble {
  0%, 100% { transform: rotate(-12deg); }
  50% { transform: rotate(12deg); }
}

@keyframes hm-jump-up {
  0% { transform: translateY(0) scale(1.12, 0.82); }
  40% { transform: translateY(-36px) scale(0.95, 1.08); }
  70% { transform: translateY(0) scale(1.1, 0.86); }
  85% { transform: translateY(-5px) scale(0.98, 1.03); }
  100% { transform: translateY(0) scale(1); }
}

@media (prefers-reduced-motion: reduce) {
  .hm-figure * {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
  }
}
</style>
