<script setup>
import { onMounted, onUnmounted } from 'vue'

let observer = null

onMounted(() => {
  // Staggered reveal for feature cards
  observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('revealed')
          observer.unobserve(entry.target)
        }
      })
    },
    { threshold: 0.1, rootMargin: '0px 0px -40px 0px' }
  )

  // Observe feature cards
  const features = document.querySelectorAll('.VPFeature')
  features.forEach((el, i) => {
    el.style.setProperty('--reveal-delay', `${i * 80}ms`)
    el.classList.add('reveal-target')
    observer.observe(el)
  })

  // Observe terminal
  const terminal = document.querySelector('.terminal')
  if (terminal) {
    terminal.classList.add('reveal-target')
    terminal.style.setProperty('--reveal-delay', '0ms')
    observer.observe(terminal)
  }

  // Observe home page custom sections
  const sections = document.querySelectorAll('.home-section')
  sections.forEach((el, i) => {
    el.style.setProperty('--reveal-delay', `${i * 100}ms`)
    el.classList.add('reveal-target')
    observer.observe(el)
  })
})

onUnmounted(() => {
  if (observer) observer.disconnect()
})
</script>

<template>
  <div class="hero-bg-effects">
    <div class="orb orb-1" />
    <div class="orb orb-2" />
    <div class="orb orb-3" />
  </div>
</template>

<style>
/* ============================================
   Animated Background Orbs
   ============================================ */
.hero-bg-effects {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  overflow: hidden;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.4;
  will-change: transform;
}

.orb-1 {
  width: 500px;
  height: 500px;
  background: radial-gradient(circle, rgba(14, 165, 233, 0.2), transparent 70%);
  top: -10%;
  left: 10%;
  animation: float-1 20s ease-in-out infinite;
}

.orb-2 {
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, rgba(56, 189, 248, 0.15), transparent 70%);
  top: 20%;
  right: 5%;
  animation: float-2 25s ease-in-out infinite;
}

.orb-3 {
  width: 350px;
  height: 350px;
  background: radial-gradient(circle, rgba(30, 64, 175, 0.12), transparent 70%);
  bottom: 10%;
  left: 30%;
  animation: float-3 22s ease-in-out infinite;
}

.dark .orb-1 {
  background: radial-gradient(circle, rgba(14, 165, 233, 0.12), transparent 70%);
}
.dark .orb-2 {
  background: radial-gradient(circle, rgba(56, 189, 248, 0.08), transparent 70%);
}
.dark .orb-3 {
  background: radial-gradient(circle, rgba(30, 64, 175, 0.1), transparent 70%);
}

@keyframes float-1 {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(60px, 40px) scale(1.05); }
  66% { transform: translate(-30px, 20px) scale(0.95); }
}

@keyframes float-2 {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(-50px, 30px) scale(0.95); }
  66% { transform: translate(40px, -20px) scale(1.08); }
}

@keyframes float-3 {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(30px, -40px) scale(1.04); }
  66% { transform: translate(-40px, 30px) scale(0.96); }
}

/* ============================================
   Hero Entrance Animations
   ============================================ */
@keyframes hero-fade-up {
  from {
    opacity: 0;
    transform: translateY(24px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes hero-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes hero-scale-in {
  from {
    opacity: 0;
    transform: translateY(16px) scale(0.96);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.VPHero .name {
  animation: hero-fade-up 0.7s cubic-bezier(0.16, 1, 0.3, 1) both;
  animation-delay: 0.1s;
}

.VPHero .text {
  animation: hero-fade-up 0.7s cubic-bezier(0.16, 1, 0.3, 1) both;
  animation-delay: 0.25s;
}

.VPHero .tagline {
  animation: hero-fade-up 0.7s cubic-bezier(0.16, 1, 0.3, 1) both;
  animation-delay: 0.4s;
}

.VPHero .actions {
  animation: hero-fade-up 0.7s cubic-bezier(0.16, 1, 0.3, 1) both;
  animation-delay: 0.55s;
}

.VPHero .image-container {
  animation: hero-fade-in 1s ease both;
  animation-delay: 0.3s;
}

/* ============================================
   Scroll Reveal for Features & Terminal
   ============================================ */
.reveal-target {
  opacity: 0;
  transform: translateY(28px);
  transition:
    opacity 0.6s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.6s cubic-bezier(0.16, 1, 0.3, 1);
  transition-delay: var(--reveal-delay, 0ms);
}

.reveal-target.revealed {
  opacity: 1;
  transform: translateY(0);
}

/* ============================================
   Terminal Cursor Blink
   ============================================ */
@keyframes cursor-blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

.terminal-body .t-cursor {
  animation: cursor-blink 1s step-end infinite;
}

/* ============================================
   Feature Card Icon Hover Animation
   ============================================ */
.VPFeatures .VPFeature .icon {
  transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}

.VPFeatures .VPFeature:hover .icon {
  transform: scale(1.08);
  background: linear-gradient(135deg, rgba(14, 165, 233, 0.16), rgba(56, 189, 248, 0.1));
  border-color: rgba(14, 165, 233, 0.25);
  box-shadow: 0 4px 16px rgba(14, 165, 233, 0.12);
}

.dark .VPFeatures .VPFeature:hover .icon {
  background: linear-gradient(135deg, rgba(14, 165, 233, 0.2), rgba(56, 189, 248, 0.1));
  border-color: rgba(56, 189, 248, 0.2);
  box-shadow: 0 4px 16px rgba(14, 165, 233, 0.1);
}

/* ============================================
   Terminal Entrance
   ============================================ */
@keyframes terminal-glow {
  0%, 100% { box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2), 0 0 0 1px rgba(255, 255, 255, 0.05); }
  50% { box-shadow: 0 8px 32px rgba(14, 165, 233, 0.08), 0 0 0 1px rgba(14, 165, 233, 0.08); }
}

.terminal.revealed {
  animation: terminal-glow 4s ease-in-out 1s infinite;
}

.dark .terminal.revealed {
  animation: terminal-glow-dark 4s ease-in-out 1s infinite;
}

@keyframes terminal-glow-dark {
  0%, 100% { box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4), 0 0 0 1px rgba(255, 255, 255, 0.05); }
  50% { box-shadow: 0 8px 32px rgba(14, 165, 233, 0.12), 0 0 0 1px rgba(56, 189, 248, 0.1); }
}

/* ============================================
   Reduce motion preference
   ============================================ */
@media (prefers-reduced-motion: reduce) {
  .orb { animation: none !important; }
  .VPHero .name,
  .VPHero .text,
  .VPHero .tagline,
  .VPHero .actions,
  .VPHero .image-container {
    animation: none !important;
    opacity: 1 !important;
  }
  .reveal-target {
    opacity: 1 !important;
    transform: none !important;
    transition: none !important;
  }
  .terminal-body .t-cursor {
    animation: none !important;
  }
  .terminal.revealed,
  .dark .terminal.revealed {
    animation: none !important;
  }
}
</style>
