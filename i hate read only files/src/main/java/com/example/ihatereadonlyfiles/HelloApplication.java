package com.example.ihatereadonlyfiles;

import javafx.animation.KeyFrame;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.beans.property.ReadOnlyProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.*;
import java.net.URL;
import java.util.*;


public class HelloApplication extends Application {
    private final List<File> audioFiles = new ArrayList<>();
    private int currentFileIndex = 0;
    public MediaPlayer mediaPlayer;
    private ListView<String> playlistListView;
    private final Map<String, Double> playbackPositions = new HashMap<>();
    private Label seekLabel;
    private Slider seekSlider;
    private Label nowPlayingLabel;
    private Label nowPlayingHeaderLabel;
    private Timeline backgroundAnimationTimeline;
    private Stage visualizerStage;
    private boolean isShuffleMode = false;

    private BorderPane root;
    private MenuBar menuBar;
    private String currentThemeName = "default";

    private GraphicsContext gc;
    private Canvas visualizerCanvas;

    private float[] smoothedMagnitudes = new float[64];
    private final double smoothingFactor = 0.1;
    private double rotationAngle = 0;
    private double beatPulseRadius = 0;
    private final double beatPulseShrinkRate = 2.0;
    private Color baseColor = Color.CYAN;

    private final Font labelFont = Font.font("Impact", 12);
    private final Font buttonFont = Font.font("Impact", 12);

    private DropShadow visualizerGlowEffect;
    private DropShadow orbGlowEffect;

    private boolean isAudioSpectrumListenerActive = true;

    private double lastAverageMagnitude = 0;
    private final double beatThresholdFactor = 1.5;

    private double crossSize = 0;
    private final double crossShrinkRate = 1.0;
    private double outerHaloOpacity = 0.0;
    private final double outerHaloDecayRate = 0.04;

    private final List<Star> stars = new ArrayList<>();
    private final int MAX_STARS = 50;
    private final Random random = new Random();
    private final double WARP_SPEED = 2.5;

    private Slider volumeSlider;

    public enum VisualizerType {
        NEON_ORB,
        BAR_SPECTRUM,
        RADIAL_WAVE,
        PARTICLE_CLOUD,
        EQUALIZER_GLOW,
        PULSING_RINGS,
        CIRCLE_BEAT,
        TUNNEL_WARP
    } // STOP LAGGING GODDAMN IT FUCK FUCK FUCK FUCK

    private VisualizerType currentVisualizerType = VisualizerType.NEON_ORB;

    private class Star {
        double x, y, z;
        double pz;
        double speed;
        Color color;

        Star() {
            reset();
            this.color = Color.hsb(random.nextDouble() * 360, 0.8, 0.8);
        }

        public void reset() {
            if (visualizerCanvas == null) {
                System.err.println("Error: visualizerCanvas is null during Star initialization.");
                return;
            }
            this.x = random.nextDouble() * visualizerCanvas.getWidth();
            this.y = random.nextDouble() * visualizerCanvas.getHeight();
            this.z = 1.0;
            this.pz = this.z;
            this.speed = 0.5 + random.nextDouble() * 1.5;
        }

        public void update() {
            pz = z;
            z -= WARP_SPEED * speed * 0.01;
            if (z < 0) {
                reset();
            }
        }

        public void draw(GraphicsContext gc) {
            if (visualizerCanvas == null) {
                return;
            }

            double width = visualizerCanvas.getWidth();
            double height = visualizerCanvas.getHeight();
            double centerX = width / 2;
            double centerY = height / 2;

            double scale = 2.0;
            double sx = (x - centerX) * (scale / z) + centerX;
            double sy = (y - centerY) * (scale / z) + centerY;

            double psx = (x - centerX) * (scale / pz) + centerX;
            double psy = (y - centerY) * (scale / pz) + centerY;

            if (z > 0.01 && pz > 0.01) {
                double size = (1 - z) * 5 + 1;
                double brightnessFactor = (1 - z) * 0.8 + 0.2;
                gc.setStroke(color.deriveColor(0, 1, brightnessFactor, 1));
                gc.setLineWidth(size);
                gc.setLineCap(StrokeLineCap.ROUND);
                gc.strokeLine(psx, psy, sx, sy);
            }
        }
    }

    public void load(String filepath) {
        if (mediaPlayer != null) {
            playbackPositions.put(audioFiles.get(currentFileIndex).getAbsolutePath(), mediaPlayer.getCurrentTime().toMillis());
            mediaPlayer.dispose();
        }

        File audioFile = new File(filepath);
        Media media = new Media(audioFile.toURI().toString());
        mediaPlayer = new MediaPlayer(media);

        if (volumeSlider != null) {
            mediaPlayer.setVolume(volumeSlider.getValue());
        }

        nowPlayingLabel.setText("Now Playing: " + audioFile.getName().replaceFirst("[.][^.]+$", ""));
        //regex is cancer, do i care? no.

        mediaPlayer.setOnEndOfMedia(() -> {
            if (isShuffleMode) {
                int nextIndex;
                do {
                    nextIndex = new Random().nextInt(audioFiles.size());
                } while (audioFiles.size() > 1 && nextIndex == currentFileIndex);
                currentFileIndex = nextIndex;
            } else {
                currentFileIndex++;
                if (currentFileIndex >= audioFiles.size()) {
                    currentFileIndex = 0;
                }
            }
            currentVisualizerType = VisualizerType.values()[(currentVisualizerType.ordinal() + 1) % VisualizerType.values().length];
            System.out.println("Switched Visualizer to: " + currentVisualizerType);
            //about as useful as saying your breathing air.

            load(audioFiles.get(currentFileIndex).getAbsolutePath());
            mediaPlayer.play();
            updatePlaylistSelection();
        });

        Double lastPosition = playbackPositions.get(filepath);
        if (lastPosition != null) {
            mediaPlayer.seek(Duration.millis(lastPosition));
        }

        if (isAudioSpectrumListenerActive) {
            mediaPlayer.setAudioSpectrumListener((_, _, magnitudes, _) -> {
                double currentAverageMagnitude = 0;
                for (float mag : magnitudes) {
                    currentAverageMagnitude += Math.max(0, mag + 60);
                }
                currentAverageMagnitude /= magnitudes.length;

                boolean beatDetected = false;
                if (lastAverageMagnitude > 0 && currentAverageMagnitude > lastAverageMagnitude * beatThresholdFactor) {
                    beatDetected = true;
                    crossSize = Math.min(visualizerCanvas.getWidth(), visualizerCanvas.getHeight()) * 0.1;
                    outerHaloOpacity = 0.5;
                }
                lastAverageMagnitude = currentAverageMagnitude;

                drawSpectrum(gc, magnitudes, beatDetected);
            });
        } else {
            mediaPlayer.setAudioSpectrumListener(null);
        }

        mediaPlayer.currentTimeProperty().addListener((_, _, newValue) -> {
            if (newValue != null && mediaPlayer.getTotalDuration().toMillis() > 0 && !seekSlider.isValueChanging()) {
                seekLabel.setText(formatTime(newValue.toSeconds()));
                seekSlider.setValue(newValue.toSeconds() / mediaPlayer.getTotalDuration().toSeconds());
            }
        });
    }

    private void drawSpectrum(GraphicsContext gc, float[] magnitudes, boolean beatDetected) {
        if (!visualizerStage.isShowing() || !isAudioSpectrumListenerActive) {
            return;
        }

        double width = visualizerCanvas.getWidth();
        double height = visualizerCanvas.getHeight();
        double centerX = width / 2;
        double centerY = height / 2;
        double hueShift = (System.currentTimeMillis() % 10000) / 10000.0 * 360;

        double avgMagnitude = 0;
        for (float mag : magnitudes) {
            avgMagnitude += Math.max(0, mag + 60);
        }
        avgMagnitude /= magnitudes.length;

        for (int i = 0; i < 64; i++) {
            float mag = (i < magnitudes.length) ? magnitudes[i] : -60f;
            smoothedMagnitudes[i] += (mag - smoothedMagnitudes[i]) * smoothingFactor;
        }

        drawWarpDrive();

        visualizerGlowEffect.setColor(baseColor.deriveColor(0, 1, 1, 0.7));
        orbGlowEffect.setColor(baseColor.deriveColor(0, 1, 1, 0.8));

        switch (currentVisualizerType) {
            case NEON_ORB:
                drawNeonOrbVisualizer(gc, magnitudes, beatDetected, width, height, centerX, centerY, hueShift, avgMagnitude);
                break;
            case BAR_SPECTRUM:
                drawBarSpectrumVisualizer(gc, magnitudes, beatDetected, width, height, hueShift);
                break;
            case RADIAL_WAVE:
                drawRadialWaveVisualizer(gc, magnitudes, beatDetected, width, height, centerX, centerY, hueShift, avgMagnitude);
                break;
            case PARTICLE_CLOUD:
                drawParticleCloudVisualizer(gc, magnitudes, beatDetected, width, height, centerX, centerY, avgMagnitude);
                break;
            case EQUALIZER_GLOW:
                drawEqualizerGlowVisualizer(gc, magnitudes, beatDetected, width, height, hueShift);
                break;
            case PULSING_RINGS:
                drawPulsingRingsVisualizer(gc, magnitudes, beatDetected, width, height, centerX, centerY, hueShift, avgMagnitude);
                break;
            case CIRCLE_BEAT:
                drawCircleBeatVisualizer(gc, magnitudes, beatDetected, width, height, centerX, centerY, hueShift, avgMagnitude);
                break;
            case TUNNEL_WARP:
                drawTunnelWarpVisualizer(gc, magnitudes, beatDetected, width, height, centerX, centerY, hueShift, avgMagnitude);
                break;
        }

        if (currentVisualizerType == VisualizerType.NEON_ORB) {
            rotationAngle += 0.5;
            if (rotationAngle >= 360) rotationAngle -= 360;
        }
    }

    private void drawNeonOrbVisualizer(GraphicsContext gc, float[] magnitudes, boolean beatDetected, double width, double height, double centerX, double centerY, double hueShift, double avgMag) {
        double baseRadius = Math.min(width, height) * 0.15;
        final int barCount = 64;

        gc.setLineWidth(3);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setEffect(visualizerGlowEffect);

        for (int i = 0; i < barCount; i++) {
            double angle = 2 * Math.PI * i / barCount;
            double magnitude = Math.max(0, smoothedMagnitudes[i] + 60);
            double barLength = magnitude * 2.5;

            double currentBaseRadius = baseRadius + magnitude * 0.1;
            double startX = centerX + Math.cos(angle) * currentBaseRadius;
            double startY = centerY + Math.sin(angle) * currentBaseRadius;
            double endX = centerX + Math.cos(angle) * (currentBaseRadius + barLength);
            double endY = centerY + Math.sin(angle) * (currentBaseRadius + barLength);

            Color barBaseColor = Color.hsb((hueShift + i * 5) % 360, 1.0, 1.0);
            gc.setStroke(barBaseColor);
            gc.strokeLine(startX, startY, endX, endY);
        }
        gc.setEffect(null);

        gc.setLineCap(StrokeLineCap.BUTT);
        //haha butt

        if (beatPulseRadius > 0) {
            Color pulseColor = Color.hsb(hueShift, 0.9, 1.0, Math.min(0.5, beatPulseRadius / (baseRadius * 1.8) * 0.8));
            gc.setStroke(pulseColor);
            gc.setLineWidth(beatPulseRadius * 0.05);
            gc.setEffect(visualizerGlowEffect);
            gc.strokeOval(centerX - beatPulseRadius, centerY - beatPulseRadius, beatPulseRadius * 2, beatPulseRadius * 2);
            gc.setEffect(null);
            beatPulseRadius -= beatPulseShrinkRate * 2;
        }

        double currentInnerRingRadius = baseRadius * 0.8 + avgMag * 0.3;
        gc.setStroke(Color.hsb((hueShift + 90) % 360, 0.9, 1.0, 0.7));
        gc.setLineWidth(2);
        gc.setEffect(visualizerGlowEffect);
        gc.strokeOval(centerX - currentInnerRingRadius, centerY - currentInnerRingRadius,
                currentInnerRingRadius * 2, currentInnerRingRadius * 2);
        gc.setEffect(null);

        double currentOuterRingRadius = baseRadius * 1.2 + avgMag * 0.4;
        gc.setStroke(Color.hsb((hueShift + 270) % 360, 0.9, 1.0, 0.7));
        gc.setLineWidth(2);
        gc.setEffect(visualizerGlowEffect);
        gc.strokeOval(centerX - currentOuterRingRadius, centerY - currentOuterRingRadius,
                currentOuterRingRadius * 2, currentOuterRingRadius * 2);
        gc.setEffect(null);

        if (crossSize > 0) {
            Color crossColor = Color.hsb((hueShift + 180) % 360, 1.0, 1.0, Math.min(0.8, crossSize / (Math.min(width, height) * 0.1) * 0.8));
            gc.setStroke(crossColor);
            gc.setLineWidth(crossSize * 0.1);
            gc.setEffect(visualizerGlowEffect);
            gc.save();
            gc.translate(centerX, centerY);
            gc.rotate(rotationAngle);
            gc.strokeLine(-crossSize, 0, crossSize, 0);
            gc.strokeLine(0, -crossSize, 0, crossSize);
            gc.restore();
            gc.setEffect(null);
            crossSize -= crossShrinkRate;
        }

        double orbRadius = 10 + avgMag * 0.5;

        Color orbColor = Color.hsb((hueShift + 180) % 360, 0.8, 1.0, 0.8);
        gc.setFill(orbColor);

        orbGlowEffect.setColor(orbColor.deriveColor(0, 1, 1, 0.8));
        orbGlowEffect.setRadius(15 + avgMag * 0.5);
        gc.setEffect(orbGlowEffect);

        gc.fillOval(centerX - orbRadius, centerY - orbRadius, orbRadius * 2, orbRadius * 2);

        if (outerHaloOpacity > 0) {
            double haloRadius = Math.min(width, height) * 0.4;
            Color haloColor = Color.hsb(hueShift, 0.7, 1.0, outerHaloOpacity);
            gc.setStroke(haloColor);
            gc.setLineWidth(5);
            gc.setEffect(visualizerGlowEffect);
            gc.strokeOval(centerX - haloRadius, centerY - haloRadius, haloRadius * 2, haloRadius * 2);
            gc.setEffect(null);
            outerHaloOpacity -= outerHaloDecayRate;
            if (outerHaloOpacity < 0) outerHaloOpacity = 0;
        }

        gc.setEffect(null);
    }

    private void drawBarSpectrumVisualizer(GraphicsContext gc, float[] magnitudes, boolean beatDetected, double width, double height, double hueShift) {
        double barWidth = width / 64;

        gc.setEffect(visualizerGlowEffect);
        for (int i = 0; i < 64; i++) {
            double magnitude = Math.max(0, smoothedMagnitudes[i] + 60);
            double barHeight = magnitude * 2.0;

            Color barColor = Color.hsb((hueShift + i * 3) % 360, 1.0, 1.0, 0.8);
            gc.setFill(barColor);
            gc.fillRect(i * barWidth, height - barHeight, barWidth * 0.8, barHeight);
        }
        gc.setEffect(null);
    }

    private void drawRadialWaveVisualizer(GraphicsContext gc, float[] magnitudes, boolean beatDetected, double width, double height, double centerX, double centerY, double hueShift, double avgMag) {
        double maxRadius = Math.min(width, height) / 2 * 0.9;

        gc.setLineWidth(2);
        gc.setEffect(visualizerGlowEffect);
        for (int i = 0; i < 64; i++) {
            double magnitude = Math.max(0, smoothedMagnitudes[i] + 60);
            double radius = maxRadius * (magnitude / 60.0);

            Color waveColor = Color.hsb((hueShift + i * 5) % 360, 1.0, 1.0, 0.7);
            gc.setStroke(waveColor);
            gc.strokeOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        }
        gc.setEffect(null);

        double orbRadius = 5 + avgMag * 0.5;
        Color orbColor = Color.hsb((hueShift + 180) % 360, 0.8, 1.0, 0.8);
        gc.setFill(orbColor);
        orbGlowEffect.setColor(orbColor.deriveColor(0, 1, 1, 0.8));
        orbGlowEffect.setRadius(10 + avgMag * 0.5);
        gc.setEffect(orbGlowEffect);
        gc.fillOval(centerX - orbRadius, centerY - orbRadius, orbRadius * 2, orbRadius * 2);
        gc.setEffect(null);
    }

    private void drawParticleCloudVisualizer(GraphicsContext gc, float[] magnitudes, boolean beatDetected, double width, double height, double centerX, double centerY, double avgMagnitude) {
        double particleCount = 20 + avgMagnitude;
        double particleMaxRadius = 2 + avgMagnitude * 0.03;

        gc.setEffect(visualizerGlowEffect);

        for (int i = 0; i < particleCount; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double distance = random.nextDouble() * Math.min(width, height) * 0.4;
            double x = centerX + Math.cos(angle) * distance;
            double y = centerY + Math.sin(angle) * distance;

            double size = random.nextDouble() * particleMaxRadius;
            Color particleColor = Color.hsb(random.nextDouble() * 360, 0.8, 1.0, 0.6);
            gc.setFill(particleColor);
            gc.fillOval(x - size / 2, y - size / 2, size, size);
        }
        gc.setEffect(null);

        if (beatDetected) {
            beatPulseRadius = Math.min(width, height) * 0.1;
        }
        if (beatPulseRadius > 0) {
            Color pulseColor = Color.hsb(random.nextDouble() * 360, 0.9, 1.0, Math.min(0.5, beatPulseRadius / (Math.min(width, height) * 0.1) * 0.8));
            gc.setStroke(pulseColor);
            gc.setLineWidth(beatPulseRadius * 0.1);
            gc.setEffect(visualizerGlowEffect);
            gc.strokeOval(centerX - beatPulseRadius, centerY - beatPulseRadius, beatPulseRadius * 2, beatPulseRadius * 2);
            gc.setEffect(null);
            beatPulseRadius -= beatPulseShrinkRate;
        }
    }

    private void drawEqualizerGlowVisualizer(GraphicsContext gc, float[] magnitudes, boolean beatDetected, double width, double height, double hueShift) {
        double barWidth = width / 64;

        gc.setEffect(visualizerGlowEffect);
        for (int i = 0; i < 64; i++) {
            double magnitude = Math.max(0, smoothedMagnitudes[i] + 60);
            double barHeight = magnitude * 1.5;
            double x = i * barWidth;
            double y = height - barHeight;

            Color endColor = Color.hsb((hueShift + i * 4) % 360, 1.0, 1.0, 1.0);
            gc.setFill(endColor.deriveColor(0, 1, 1, 0.8));
            gc.fillRect(x, y, barWidth * 0.8, barHeight);

            gc.setStroke(endColor.brighter());
            gc.setLineWidth(1);
            gc.strokeLine(x, y, x + barWidth * 0.8, y);
        }
        gc.setEffect(null);

        if (beatDetected) {
            Color flashColor = Color.hsb(hueShift, 0.2, 1.0, 0.1);
            gc.setFill(flashColor);
            gc.setEffect(visualizerGlowEffect);
            gc.fillRect(0, 0, width, height);
            gc.setEffect(null);
        }
    }

    private void drawPulsingRingsVisualizer(GraphicsContext gc, float[] magnitudes, boolean beatDetected, double width, double height, double centerX, double centerY, double hueShift, double avgMagnitude) {
        double maxRadius = Math.min(width, height) * 0.4;

        double baseRingRadius = maxRadius * (avgMagnitude / 60.0);
        baseRingRadius = Math.min(baseRingRadius, maxRadius);

        gc.setLineWidth(3);
        gc.setEffect(visualizerGlowEffect);

        int numberOfRings = 5;
        for (int i = 0; i < numberOfRings; i++) {
            double currentRadius = baseRingRadius * (1 + i * 0.1) + (beatDetected ? beatPulseRadius * 0.5 : 0);
            Color ringColor = Color.hsb((hueShift + i * 20) % 360, 0.9, 1.0, 0.7 - i * 0.1);
            gc.setStroke(ringColor);
            gc.strokeOval(centerX - currentRadius, centerY - currentRadius, currentRadius * 2, currentRadius * 2);
        }
        gc.setEffect(null);

        if (beatDetected) {
            beatPulseRadius = maxRadius * 0.2;
        }
        if (beatPulseRadius > 0) {
            Color pulseColor = Color.hsb(hueShift + 180 % 360, 0.9, 1.0, Math.min(0.8, beatPulseRadius / (maxRadius * 0.2) * 0.8));
            gc.setFill(pulseColor);
            orbGlowEffect.setRadius(10 + beatPulseRadius);
            gc.setEffect(orbGlowEffect);
            double currentOrbRadius = 10 + beatPulseRadius;
            gc.fillOval(centerX - currentOrbRadius, centerY - currentOrbRadius, currentOrbRadius * 2, currentOrbRadius * 2);
            gc.setEffect(null);
            beatPulseRadius -= beatPulseShrinkRate;
        }
    }

    private void drawCircleBeatVisualizer(GraphicsContext gc, float[] magnitudes, boolean beatDetected, double width, double height, double centerX, double centerY, double hueShift, double avgMagnitude) {
        double minRadius = Math.min(width, height) * 0.05;
        double maxRadius = Math.min(width, height) * 0.45;

        gc.setLineWidth(2);
        gc.setEffect(visualizerGlowEffect);

        final int numCircles = 64;
        for (int i = 0; i < numCircles; i++) {
            double magnitude = Math.max(0, smoothedMagnitudes[i] + 60);
            double currentRadius = minRadius + (magnitude / 60.0) * (maxRadius - minRadius);

            double angle = 2 * Math.PI * i / numCircles;
            double x = centerX + Math.cos(angle) * currentRadius * 0.5;
            double y = centerY + Math.sin(angle) * currentRadius * 0.5;

            Color circleColor = Color.hsb((hueShift + i * 5) % 360, 1.0, 1.0, 0.8);
            gc.setStroke(circleColor);
            gc.strokeOval(x - currentRadius * 0.5, y - currentRadius * 0.5, currentRadius, currentRadius);
        }
        gc.setEffect(null);

        double centerOrbRadius = minRadius + avgMagnitude * 0.5;
        Color centerOrbColor = Color.hsb((hueShift + 180) % 360, 0.8, 1.0, 0.9);
        gc.setFill(centerOrbColor);
        orbGlowEffect.setRadius(10 + avgMagnitude * 0.5);
        gc.setEffect(orbGlowEffect);
        gc.fillOval(centerX - centerOrbRadius, centerY - centerOrbRadius, centerOrbRadius * 2, centerOrbRadius * 2);
        gc.setEffect(null);

        if (beatDetected) {
            beatPulseRadius = maxRadius * 0.8;
        }
        if (beatPulseRadius > 0) {
            Color pulseColor = Color.hsb(hueShift, 0.9, 1.0, Math.min(0.5, beatPulseRadius / (maxRadius * 0.8) * 0.8));
            gc.setStroke(pulseColor);
            gc.setLineWidth(beatPulseRadius * 0.1);
            gc.setEffect(visualizerGlowEffect);
            gc.strokeOval(centerX - beatPulseRadius, centerY - beatPulseRadius, beatPulseRadius * 2, beatPulseRadius * 2);
            gc.setEffect(null);
            beatPulseRadius -= beatPulseShrinkRate;
        }
    } // just shapes and beats hahahahahahahaha im gunna kill myself

    private void drawTunnelWarpVisualizer(GraphicsContext gc, float[] magnitudes, boolean beatDetected, double width, double height, double centerX, double centerY, double hueShift, double avgMagnitude) {
        gc.setLineCap(StrokeLineCap.BUTT);
        gc.setEffect(visualizerGlowEffect);

        final int numSegments = 64;
        final double maxSegmentSize = Math.min(width, height) * 0.8;

        for (int i = 0; i < numSegments; i++) {
            double magnitude = Math.max(0, smoothedMagnitudes[i] + 60);
            double segmentSize = maxSegmentSize * (1 - (double) i / numSegments) * (1 + magnitude * 0.005);

            Color segmentColor = Color.hsb((hueShift + i * 3) % 360, 1.0, 1.0, 0.2 + (0.8 * (1 - (double) i / numSegments)));
            gc.setStroke(segmentColor);
            gc.setLineWidth(2 + magnitude * 0.05);

            gc.strokeRect(centerX - segmentSize / 2, centerY - segmentSize / 2, segmentSize, segmentSize);
        }
        gc.setEffect(null);

        if (beatDetected) {
            beatPulseRadius = 10 + avgMagnitude * 0.5;
        }
        if (beatPulseRadius > 0) {
            Color wormholeColor = Color.hsb((hueShift + 180) % 360, 0.9, 1.0, Math.min(1.0, beatPulseRadius / (10 + avgMagnitude * 0.5) * 0.8));
            gc.setFill(wormholeColor);
            orbGlowEffect.setRadius(10 + beatPulseRadius);
            gc.setEffect(orbGlowEffect);
            gc.fillOval(centerX - beatPulseRadius, centerY - beatPulseRadius, beatPulseRadius * 2, beatPulseRadius * 2);
            gc.setEffect(null);
            beatPulseRadius -= beatPulseShrinkRate * 0.5;
        }
    }


    private String formatTime(double seconds) {
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        int secs = (int) (seconds % 60);
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    } //THIS FUCKING SUCKS REMAKE LATER

    @Override
    public void start(Stage stage) {

        visualizerGlowEffect = new DropShadow(BlurType.GAUSSIAN, baseColor, 6, 0.3, 0, 0);
        orbGlowEffect = new DropShadow(BlurType.GAUSSIAN, baseColor, 10, 0.8, 0, 0);

        root = new BorderPane();
        root.getStyleClass().add("root-pane");

        VBox topSection = new VBox();
        topSection.getChildren().add(createMenuBar(stage));
        topSection.getChildren().add(createNowPlayingBox());
        root.setTop(topSection);

        VBox centerPane = new VBox();
        centerPane.getChildren().add(createPlaylistListView());
        root.setCenter(centerPane);

        root.setBottom(createControlsBox());

        visualizerStage = new Stage();
        visualizerCanvas = new Canvas();
        gc = visualizerCanvas.getGraphicsContext2D();

        StackPane visualizerRoot = new StackPane();
        visualizerRoot.getChildren().add(visualizerCanvas);

        visualizerCanvas.widthProperty().bind(visualizerRoot.widthProperty());
        visualizerCanvas.heightProperty().bind(visualizerRoot.heightProperty());

        Scene visualizerScene = new Scene(visualizerRoot);
        visualizerStage.setScene(visualizerScene);
        visualizerStage.setTitle("Visualizer");
        visualizerStage.setWidth(600);
        visualizerStage.setHeight(400);

        for (int i = 0; i < MAX_STARS; i++) {
            stars.add(new Star());
        }

        backgroundAnimationTimeline = new Timeline(new KeyFrame(Duration.millis(16.67), _ -> {
            for (Star star : stars) {
                star.update();
            }
        }));
        backgroundAnimationTimeline.setCycleCount(Timeline.INDEFINITE);
        backgroundAnimationTimeline.play();

        Scene scene = new Scene(root, 1000, 600);
        URL cssUrl = getClass().getResource("/com/example/ihatereadonlyfiles/styles.css");
        if (cssUrl == null) {
            cssUrl = getClass().getResource("/styles.css");
        }

        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        } else {
            System.err.println("Error loading styles.css: Make sure styles.css is in the correct package path (e.g., src/main/resources/com/example/ihatereadonlyfiles/styles.css) OR directly in src/main/resources/styles.css.");
        }

        stage.setTitle("yfitopS");
        // woah spotify backwards, dont sue me please
        stage.setScene(scene);
        stage.show();

        loadAudioFilesFromDisk();
        updatePlaylistView();

        stage.iconifiedProperty().addListener((obs, wasIconified, isIconified) -> {
            if (isIconified) {
                System.out.println("Application minimized.");
                if (backgroundAnimationTimeline != null) {
                    backgroundAnimationTimeline.stop();
                }
                // so you crybaby fucks wont complain about your fps in testicle royale
                if (mediaPlayer != null) {
                    mediaPlayer.setAudioSpectrumListener(null);
                    isAudioSpectrumListenerActive = false;
                }
            } else {
                System.out.println("Application restored.");
                if (backgroundAnimationTimeline != null) {
                    backgroundAnimationTimeline.play();
                }
                if (mediaPlayer != null) {
                    mediaPlayer.setAudioSpectrumListener((_, _, magnitudes, _) -> {
                        double currentAverageMagnitude = 0;
                        for (float mag : magnitudes) {
                            currentAverageMagnitude += Math.max(0, mag + 60);
                        }
                        currentAverageMagnitude /= magnitudes.length;

                        boolean beatDetected = false;
                        if (lastAverageMagnitude > 0 && currentAverageMagnitude > lastAverageMagnitude * beatThresholdFactor) {
                            beatDetected = true;
                            crossSize = Math.min(visualizerCanvas.getWidth(), visualizerCanvas.getHeight()) * 0.1;
                            outerHaloOpacity = 0.5;
                        }
                        lastAverageMagnitude = currentAverageMagnitude;
                        drawSpectrum(gc, magnitudes, beatDetected);
                    });
                    isAudioSpectrumListenerActive = true;
                }
            }
        });
    }

    private MenuBar createMenuBar(Stage stage) {
        menuBar = new MenuBar();
        menuBar.getStyleClass().add("menu-bar");
        menuBar.setPrefWidth(Double.MAX_VALUE);
        menuBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(menuBar, Priority.ALWAYS);

        Menu fileMenu = new Menu("File");
        fileMenu.getStyleClass().add("menu");
        MenuItem openFiles = new MenuItem("Add Audiobooks to Library");
        openFiles.getStyleClass().add("menu-item");
        fileMenu.getItems().add(openFiles);

        Menu themeMenu = new Menu("Themes");
        themeMenu.getStyleClass().add("menu");
        MenuItem defaultTheme = new MenuItem("Default (Neon)");
        defaultTheme.getStyleClass().add("menu-item");
        MenuItem blackAndWhiteTheme = new MenuItem("Black and White");
        blackAndWhiteTheme.getStyleClass().add("menu-item");
        MenuItem oceanBreezeTheme = new MenuItem("Ocean Breeze");
        oceanBreezeTheme.getStyleClass().add("menu-item");
        MenuItem fierySunsetTheme = new MenuItem("Fiery Sunset");
        fierySunsetTheme.getStyleClass().add("menu-item");
        MenuItem mysticForestTheme = new MenuItem("Mystic Forest");
        mysticForestTheme.getStyleClass().add("menu-item");
        MenuItem frutigerAeroTheme = new MenuItem("Frutiger Aero");
        frutigerAeroTheme.getStyleClass().add("menu-item");
        MenuItem halfLifeTheme = new MenuItem("Half-Life");
        //cool half life theme, may need to remake later idk 💔🥀
        halfLifeTheme.getStyleClass().add("menu-item");


        themeMenu.getItems().addAll(defaultTheme, blackAndWhiteTheme,
                oceanBreezeTheme, fierySunsetTheme, mysticForestTheme,
                frutigerAeroTheme, halfLifeTheme);

        Menu settingsMenu = new Menu("Visualizer");
        settingsMenu.getStyleClass().add("menu");
        MenuItem showVisualizer = new MenuItem("Show Visualizer");
        showVisualizer.getStyleClass().add("menu-item");
        settingsMenu.getItems().add(showVisualizer);

        menuBar.getMenus().addAll(fileMenu, themeMenu, settingsMenu);

        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio Files", "*.wav", "*.mp3"));

        openFiles.setOnAction(_ -> {
            List<File> selectedFiles = fileChooser.showOpenMultipleDialog(stage);
            if (selectedFiles != null) {
                boolean filesAdded = false;
                for (File file : selectedFiles) {
                    if (!audioFiles.contains(file)) {
                        audioFiles.add(file);
                        appendFileToAudiosTxt(file.getAbsolutePath());
                        filesAdded = true;
                    }
                }
                if (filesAdded) {
                    updatePlaylistView();
                }
            }
        });

        showVisualizer.setOnAction(_ -> {
            if (!visualizerStage.isShowing()) {
                visualizerStage.show();
            } else {
                visualizerStage.toFront();
            }
        });

        defaultTheme.setOnAction(_ -> {
            currentThemeName = "default";
            baseColor = Color.CYAN;
            visualizerGlowEffect.setColor(baseColor.deriveColor(0, 1, 1, 0.7));
            orbGlowEffect.setColor(baseColor.deriveColor(0, 1, 1, 0.8));
            clearCustomStyles();
            stage.setTitle("yfitopS");
            playlistListView.refresh();
            if (isShuffleMode) {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
            } else {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
            }
            menuBar.setStyle("-fx-max-width: infinity;");
        });

        blackAndWhiteTheme.setOnAction(_ -> {
            currentThemeName = "blackAndWhite";
            baseColor = Color.WHITE;
            visualizerGlowEffect.setColor(baseColor.deriveColor(0, 1, 1, 0.7));
            orbGlowEffect.setColor(baseColor.deriveColor(0, 1, 1, 0.8));
            clearCustomStyles();

            menuBar.setStyle("-fx-background-color:#ffffff; -fx-text-fill: #000000; -fx-max-width: infinity;");
            menuBar.getMenus().forEach(menu -> {
                menu.setStyle("-fx-text-fill: #000000;");
                menu.getItems().forEach(menuItem -> menuItem.setStyle("-fx-text-fill: #000000; -fx-background-color: #ffffff;"));
            });

            if (root.getBottom() instanceof HBox) {
                root.getBottom().setStyle("-fx-background-color:#ffffff;");
                for (javafx.scene.Node node : ((HBox) root.getBottom()).getChildren()) {
                    if (node instanceof Button || node instanceof ToggleButton) {
                        node.setStyle("-fx-background-color: #000000; -fx-text-fill: #ffffff; -fx-effect: none;");
                    } else if (node instanceof Label) {
                        node.setStyle("-fx-text-fill: #000000;");
                    } else if (node instanceof Slider) {
                        Node track = node.lookup(".track");
                        if (track != null) track.setStyle("-fx-background-color: #000000; -fx-effect: none;");
                        Node thumb = node.lookup(".thumb");
                        if (thumb != null) thumb.setStyle("-fx-background-color: #666666; -fx-effect: none;");
                    }
                }
            }
            root.setStyle("-fx-background-color:#c0c0c0;");
            nowPlayingHeaderLabel.setStyle("-fx-text-fill: #000000;");
            nowPlayingLabel.setStyle("-fx-text-fill: #000000;");
            playlistListView.setStyle("-fx-background-color: #c0c0c0; -fx-control-inner-background: #c0c0c0;");
            Node bwVScrollBar = playlistListView.lookup(".scroll-bar:vertical");
            if (bwVScrollBar != null) {
                Node bwTrack = bwVScrollBar.lookup(".track");
                Node bwThumb = bwVScrollBar.lookup(".thumb");
                if (bwTrack != null) bwTrack.setStyle("-fx-background-color: #f0f0f0; -fx-background-radius: 0;");
                if (bwThumb != null) bwThumb.setStyle("-fx-background-color: #666666; -fx-background-radius: 5px;");
            }
            playlistListView.refresh();
            stage.setTitle("Back In My Day");
            if (isShuffleMode) {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #aaaaaa; -fx-text-fill: #000000;");
            } else {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #000000; -fx-text-fill: #ffffff;");
            }
        });

        oceanBreezeTheme.setOnAction(_ -> {
            currentThemeName = "oceanBreeze";
            baseColor = Color.LIGHTBLUE;
            visualizerGlowEffect.setColor(baseColor.deriveColor(0, 1, 1, 0.7));
            orbGlowEffect.setColor(Color.DEEPSKYBLUE.deriveColor(0, 1, 1, 0.8));
            clearCustomStyles();

            menuBar.setStyle("-fx-background-color: #34495e; -fx-text-fill: #3498db; -fx-max-width: infinity;");
            menuBar.getMenus().forEach(menu -> {
                menu.setStyle("-fx-text-fill: #3498db;");
                menu.getItems().forEach(menuItem -> menuItem.setStyle("-fx-text-fill: #3498db; -fx-background-color: #34495e;"));
            });

            if (root.getBottom() instanceof HBox) {
                root.getBottom().setStyle("-fx-background-color: #34495e;");
                for (javafx.scene.Node node : ((HBox) root.getBottom()).getChildren()) {
                    if (node instanceof Button || node instanceof ToggleButton) {
                        node.setStyle("-fx-background-color: #2980b9; -fx-text-fill: #ecf0f1; -fx-effect: dropshadow(gaussian, rgba(52,152,219,0.5), 5, 0.0, 0, 0);");
                    } else if (node instanceof Label) {
                        node.setStyle("-fx-text-fill: #ecf0f1;");
                    } else if (node instanceof Slider) {
                        Node track = node.lookup(".track");
                        if (track != null) track.setStyle("-fx-background-color: #2ecc71; -fx-effect: dropshadow(gaussian, rgba(46,204,113,0.5), 8, 0.0, 0, 0);");
                        Node thumb = node.lookup(".thumb");
                        if (thumb != null) thumb.setStyle("-fx-background-color: #3498db; -fx-effect: dropshadow(gaussian, rgba(52,152,219,0.8), 10, 0.0, 0, 0);");
                    }
                }
            }
            nowPlayingHeaderLabel.setStyle("-fx-text-fill: #ecf0f1;");
            nowPlayingLabel.setStyle("-fx-text-fill: #3498db;");
            playlistListView.setStyle("-fx-background-color: #2c3e50; -fx-control-inner-background: #2c3e50;");
            Node obVScrollBar = playlistListView.lookup(".scroll-bar:vertical");
            if (obVScrollBar != null) {
                Node obTrack = obVScrollBar.lookup(".track");
                Node obThumb = obVScrollBar.lookup(".thumb");
                if (obTrack != null) obTrack.setStyle("-fx-background-color: #2c3e50; -fx-background-radius: 0;");
                if (obThumb != null) obThumb.setStyle("-fx-background-color: #2980b9; -fx-background-radius: 5px;");
            }
            playlistListView.refresh();
            stage.setTitle("Ocean Breeze");
            if (isShuffleMode) {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #2ecc71; -fx-text-fill: #000000;");
            } else {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #2980b9; -fx-text-fill: #ecf0f1;");
            }
        });

        fierySunsetTheme.setOnAction(_ -> {
            currentThemeName = "fierySunset";
            baseColor = Color.ORANGE;
            visualizerGlowEffect.setColor(baseColor.deriveColor(0, 1, 1, 0.7));
            orbGlowEffect.setColor(Color.RED.deriveColor(0, 1, 1, 0.8));
            clearCustomStyles();

            menuBar.setStyle("-fx-background-color: #4a2c2a; -fx-text-fill: #e74c3c; -fx-max-width: infinity;");
            menuBar.getMenus().forEach(menu -> {
                menu.setStyle("-fx-text-fill: #e74c3c;");
                menu.getItems().forEach(menuItem -> menuItem.setStyle("-fx-text-fill: #e74c3c; -fx-background-color: #4a2c2a;"));
            });

            if (root.getBottom() instanceof HBox) {
                root.getBottom().setStyle("-fx-background-color: #4a2c2a;");
                for (javafx.scene.Node node : ((HBox) root.getBottom()).getChildren()) {
                    if (node instanceof Button || node instanceof ToggleButton) {
                        node.setStyle("-fx-background-color: #c0392b; -fx-text-fill: #ecf0f1; -fx-effect: dropshadow(gaussian, rgba(192,57,43,0.5), 5, 0.0, 0, 0);");
                    } else if (node instanceof Label) {
                        node.setStyle("-fx-text-fill: #ecf0f1;");
                    } else if (node instanceof Slider) {
                        Node track = node.lookup(".track");
                        if (track != null) track.setStyle("-fx-background-color: #f39c12; -fx-effect: dropshadow(gaussian, rgba(243,156,18,0.5), 8, 0.0, 0, 0);");
                        Node thumb = node.lookup(".thumb");
                        if (thumb != null) thumb.setStyle("-fx-background-color: #e74c3c; -fx-effect: dropshadow(gaussian, rgba(231,76,60,0.8), 10, 0.0, 0, 0);");
                    }
                }
            }
            nowPlayingHeaderLabel.setStyle("-fx-text-fill: #ecf0f1;");
            nowPlayingLabel.setStyle("-fx-text-fill: #e74c3c;");
            playlistListView.setStyle("-fx-background-color: #34222e; -fx-control-inner-background: #34222e;");
            Node fsVScrollBar = playlistListView.lookup(".scroll-bar:vertical");
            if (fsVScrollBar != null) {
                Node fsTrack = fsVScrollBar.lookup(".track");
                Node fsThumb = fsVScrollBar.lookup(".thumb");
                if (fsTrack != null) fsTrack.setStyle("-fx-background-color: #34222e; -fx-background-radius: 0;");
                if (fsThumb != null) fsThumb.setStyle("-fx-background-color: #c0392b; -fx-background-radius: 5px;");
            }
            playlistListView.refresh();
            stage.setTitle("Fiery Sunset");
            if (isShuffleMode) {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #f39c12; -fx-text-fill: #000000;");
            } else {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #c0392b; -fx-text-fill: #ecf0f1;");
            }
        });

        mysticForestTheme.setOnAction(_ -> {
            currentThemeName = "mysticForest";
            baseColor = Color.GREEN;
            visualizerGlowEffect.setColor(baseColor.deriveColor(0, 1, 1, 0.7));
            orbGlowEffect.setColor(Color.PURPLE.deriveColor(0, 1, 1, 0.8));
            clearCustomStyles();

            menuBar.setStyle("-fx-background-color: #2e4034; -fx-text-fill: #9b59b6; -fx-max-width: infinity;");
            menuBar.getMenus().forEach(menu -> {
                menu.setStyle("-fx-text-fill: #9b59b6;");
                menu.getItems().forEach(menuItem -> menuItem.setStyle("-fx-text-fill: #9b59b6; -fx-background-color: #2e4034;"));
            });

            if (root.getBottom() instanceof HBox) {
                root.getBottom().setStyle("-fx-background-color: #2e4034;");
                for (javafx.scene.Node node : ((HBox) root.getBottom()).getChildren()) {
                    if (node instanceof Button || node instanceof ToggleButton) {
                        node.setStyle("-fx-background-color: #8e44ad; -fx-text-fill: #ecf0f1; -fx-effect: dropshadow(gaussian, rgba(142,68,173,0.5), 5, 0.0, 0, 0);");
                    } else if (node instanceof Label) {
                        node.setStyle("-fx-text-fill: #ecf0f1;");
                    } else if (node instanceof Slider) {
                        Node track = node.lookup(".track");
                        if (track != null) track.setStyle("-fx-background-color: #27ae60; -fx-effect: dropshadow(gaussian, rgba(39,174,96,0.5), 8, 0.0, 0, 0);");
                        Node thumb = node.lookup(".thumb");
                        if (thumb != null) thumb.setStyle("-fx-background-color: #9b59b6; -fx-effect: dropshadow(gaussian, rgba(155,89,182,0.8), 10, 0.0, 0, 0);");
                    }
                }
            }
            nowPlayingHeaderLabel.setStyle("-fx-text-fill: #ecf0f1;");
            nowPlayingLabel.setStyle("-fx-text-fill: #9b59b6;");
            playlistListView.setStyle("-fx-background-color: #1c2b20; -fx-control-inner-background: #1c2b20;");
            Node mfVScrollBar = playlistListView.lookup(".scroll-bar:vertical");
            if (mfVScrollBar != null) {
                Node mfTrack = mfVScrollBar.lookup(".track");
                Node mfThumb = mfVScrollBar.lookup(".thumb");
                if (mfTrack != null) mfTrack.setStyle("-fx-background-color: #1c2b20; -fx-background-radius: 0;");
                if (mfThumb != null) mfThumb.setStyle("-fx-background-color: #8e44ad; -fx-background-radius: 5px;");
            }
            playlistListView.refresh();
            stage.setTitle("Mystic Forest");
            if (isShuffleMode) {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #27ae60; -fx-text-fill: #000000;");
            } else {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #8e44ad; -fx-text-fill: #ecf0f1;");
            }
        });

        frutigerAeroTheme.setOnAction(_ -> {
            currentThemeName = "frutigerAero";
            // this is so 2000's frutigerAero core! (i just put the gun to the roof of my mouth)
            baseColor = Color.LIGHTBLUE;
            visualizerGlowEffect.setColor(baseColor.deriveColor(0, 1, 1, 0.7));
            orbGlowEffect.setColor(Color.WHITE.deriveColor(0, 1, 1, 0.8));
            clearCustomStyles();

            root.setStyle("-fx-background-color: linear-gradient(to bottom, #d9f2ff, #b3e7ff); -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 10, 0.0, 0, 0);");

            menuBar.setStyle("-fx-background-color: rgba(255,255,255,0.7); -fx-border-color: rgba(255,255,255,0.8); -fx-border-width: 0 0 1px 0; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0.0, 0, 2); -fx-max-width: infinity;");
            menuBar.getMenus().forEach(menu -> {
                menu.setStyle("-fx-text-fill: #333333; -fx-font-weight: bold;");
                menu.getItems().forEach(menuItem -> menuItem.setStyle("-fx-text-fill: #333333; -fx-background-color: rgba(255,255,255,0.8);"));
            });

            if (root.getBottom() instanceof HBox) {
                root.getBottom().setStyle("-fx-background-color: rgba(255,255,255,0.6); -fx-background-radius: 15px; -fx-border-radius: 15px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 10, 0.0, 0, 0); -fx-padding: 10px;");
                for (javafx.scene.Node node : ((HBox) root.getBottom()).getChildren()) {
                    if (node instanceof Button || node instanceof ToggleButton) {
                        node.setStyle("-fx-background-color: linear-gradient(to top, rgba(255,255,255,0.5), rgba(255,255,255,0.9)); -fx-background-radius: 8px; -fx-border-radius: 8px; -fx-border-color: rgba(255,255,255,0.8); -fx-border-width: 1px; -fx-text-fill: #0077c2; -fx-font-weight: bold; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0.0, 0, 0);");
                    } else if (node instanceof Label) {
                        node.setStyle("-fx-text-fill: #0056b3; -fx-font-weight: bold;");
                    } else if (node instanceof Slider) {
                        Node track = node.lookup(".track");
                        if (track != null) track.setStyle("-fx-background-color: linear-gradient(to right, #87ceeb, #add8e6); -fx-background-radius: 5px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0.0, 0, 0);");
                        Node thumb = node.lookup(".thumb");
                        if (thumb != null) thumb.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 5px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 5, 0.0, 0, 0); -fx-border-color: #0077c2; -fx-border-width: 1px;");
                    }
                }
            }
            nowPlayingHeaderLabel.setStyle("-fx-text-fill: #0056b3; -fx-font-weight: bold;");
            nowPlayingLabel.setStyle("-fx-text-fill: #0077c2; -fx-font-weight: bold;");

            playlistListView.setStyle("-fx-background-color: rgba(255,255,255,0.5); -fx-background-radius: 10px; -fx-border-radius: 10px; -fx-border-color: rgba(255,255,255,0.7); -fx-border-width: 1px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0.0, 0, 0); -fx-control-inner-background: transparent;");
            playlistListView.refresh();

            Node faVScrollBar = playlistListView.lookup(".scroll-bar:vertical");
            if (faVScrollBar != null) {
                Node faTrack = faVScrollBar.lookup(".track");
                Node faThumb = faVScrollBar.lookup(".thumb");
                if (faTrack != null) faTrack.setStyle("-fx-background-color: rgba(255,255,255,0.3); -fx-background-radius: 0;");
                if (faThumb != null) faThumb.setStyle("-fx-background-color: rgba(0,119,194,0.7); -fx-background-radius: 5px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 3, 0.0, 0, 0);");
            }
            stage.setTitle("AeroPlayer 2007");
            if (isShuffleMode) {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: linear-gradient(to top, rgba(144,238,144,0.7), rgba(0,128,0,0.9)); -fx-background-radius: 8px; -fx-border-radius: 8px; -fx-border-color: rgba(255,255,255,0.8); -fx-border-width: 1px; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0.0, 0, 0);");
            } else {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: linear-gradient(to top, rgba(255,99,71,0.7), rgba(255,0,0,0.9)); -fx-background-radius: 8px; -fx-border-radius: 8px; -fx-border-color: rgba(255,255,255,0.8); -fx-border-width: 1px; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0.0, 0, 0);");
            }
        });


        halfLifeTheme.setOnAction(_ -> {
            currentThemeName = "halfLife";
            baseColor = Color.ORANGE;
            visualizerGlowEffect.setColor(baseColor.deriveColor(0, 1, 1, 0.7));
            orbGlowEffect.setColor(Color.DARKORANGE.deriveColor(0, 1, 1, 0.8));
            clearCustomStyles();

            root.setStyle("-fx-background-color: #1a1a1a; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 15, 0.0, 0, 0);");

            menuBar.setStyle("-fx-background-color: #333333; -fx-border-color: #555555; -fx-border-width: 0 0 1px 0; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 5, 0.0, 0, 2); -fx-max-width: infinity;");
            menuBar.getMenus().forEach(menu -> {
                menu.setStyle("-fx-text-fill: #cccccc; -fx-font-family: 'Consolas', monospace;");
                menu.getItems().forEach(menuItem -> menuItem.setStyle("-fx-text-fill: #cccccc; -fx-background-color: #222222; -fx-font-family: 'Consolas', monospace;"));
            });

            if (root.getBottom() instanceof HBox) {
                root.getBottom().setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #444444; -fx-border-width: 1px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0.0, 0, 0); -fx-padding: 10px;");
                for (javafx.scene.Node node : ((HBox) root.getBottom()).getChildren()) {
                    if (node instanceof Button || node instanceof ToggleButton) {
                        node.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #ff9900; -fx-font-family: 'Consolas', monospace; -fx-font-weight: bold; -fx-effect: innershadow(gaussian, rgba(0,0,0,0.4), 5, 0.0, 0, 0); -fx-border-color: #666666; -fx-border-width: 1px;");
                    } else if (node instanceof Label) {
                        node.setStyle("-fx-text-fill: #ffcc00; -fx-font-family: 'Consolas', monospace;");
                    } else if (node instanceof Slider) {
                        Node track = node.lookup(".track");
                        if (track != null) track.setStyle("-fx-background-color: #666666; -fx-background-radius: 2px; -fx-effect: innershadow(gaussian, rgba(0,0,0,0.3), 3, 0.0, 0, 0);");
                        Node thumb = node.lookup(".thumb");
                        if (thumb != null) thumb.setStyle("-fx-background-color: #ff9900; -fx-background-radius: 3px; -fx-effect: dropshadow(gaussian, rgba(255,153,0,0.5), 8, 0.0, 0, 0); -fx-border-color: #ffcc00; -fx-border-width: 1px;");
                    }
                }
            }
            nowPlayingHeaderLabel.setStyle("-fx-text-fill: #ffcc00; -fx-font-family: 'Consolas', monospace;");
            nowPlayingLabel.setStyle("-fx-text-fill: #ff9900; -fx-font-family: 'Consolas', monospace;");

            playlistListView.setStyle("-fx-background-color: #222222; -fx-border-color: #444444; -fx-border-width: 1px; -fx-effect: innershadow(gaussian, rgba(0,0,0,0.3), 5, 0.0, 0, 0); -fx-control-inner-background: transparent; -fx-background-image: url('/com/example/ihatereadonlyfiles/grid_texture.png'); -fx-background-repeat: repeat;");
            playlistListView.refresh();

            Node hlVScrollBar = playlistListView.lookup(".scroll-bar:vertical");
            if (hlVScrollBar != null) {
                Node hlTrack = hlVScrollBar.lookup(".track");
                Node hlThumb = hlVScrollBar.lookup(".thumb");
                if (hlTrack != null) hlTrack.setStyle("-fx-background-color: #333333; -fx-background-radius: 0;");
                if (hlThumb != null) hlThumb.setStyle("-fx-background-color: #ff9900; -fx-background-radius: 3px; -fx-effect: dropshadow(gaussian, rgba(255,153,0,0.3), 5, 0.0, 0, 0);");
            }
            stage.setTitle("Black Mesa Audio Player");
            if (isShuffleMode) {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #2ecc71; -fx-text-fill: #000000; -fx-font-family: 'Consolas', monospace; -fx-font-weight: bold; -fx-effect: innershadow(gaussian, rgba(0,0,0,0.4), 5, 0.0, 0, 0); -fx-border-color: #666666; -fx-border-width: 1px;");
            } else {
                ((ToggleButton) root.lookup(".shuffle-button")).setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #ff9900; -fx-font-family: 'Consolas', monospace; -fx-font-weight: bold; -fx-effect: innershadow(gaussian, rgba(0,0,0,0.4), 5, 0.0, 0, 0); -fx-border-color: #666666; -fx-border-width: 1px;");
            }
        });


        return menuBar;
    }

    private void clearCustomStyles() {
        root.setStyle("");
        root.getStyleClass().setAll("root-pane");

        menuBar.setStyle("");
        menuBar.getStyleClass().setAll("menu-bar");
        menuBar.setPrefWidth(Double.MAX_VALUE);
        menuBar.setMaxWidth(Double.MAX_VALUE);

        menuBar.getMenus().forEach(menu -> {
            menu.setStyle("");
            menu.getStyleClass().setAll("menu");
            menu.getItems().forEach(menuItem -> {
                menuItem.setStyle("");
                menuItem.getStyleClass().setAll("menu-item");
            });
        });

        if (root.getBottom() instanceof HBox) {
            HBox controlsBox = (HBox) root.getBottom();
            controlsBox.setStyle("");
            controlsBox.getStyleClass().setAll("controls-box");

            for (javafx.scene.Node node : controlsBox.getChildren()) {
                node.setStyle("");
                node.setScaleX(1.0);
                node.setScaleY(1.0);

                if (node instanceof Button || node instanceof ToggleButton) {
                    node.getStyleClass().setAll("control-button");
                } else if (node instanceof Label) {
                    if (node == seekLabel) {
                        node.getStyleClass().setAll("seek-label");
                    } else if (node == nowPlayingHeaderLabel) {
                        node.getStyleClass().setAll("now-playing-label");
                    } else if (node == nowPlayingLabel) {
                        node.getStyleClass().setAll("track-info-label");
                    } else {
                        node.getStyleClass().setAll("volume-label");
                    }
                } else if (node instanceof Slider) {
                    node.getStyleClass().setAll("slider");
                    Node track = node.lookup(".track");
                    if (track != null) track.setStyle("");
                    Node thumb = node.lookup(".thumb");
                    if (thumb != null) thumb.setStyle("");
                }

                node.setOnMouseEntered(null);
                node.setOnMouseExited(null);
                node.setOnMousePressed(null);
                node.setOnMouseReleased(null);

                if (node instanceof Button) {
                    Button button = (Button) node;
                    button.setOnMousePressed(e -> {
                        ScaleTransition st = new ScaleTransition(Duration.millis(100), button);
                        st.setToX(0.9);
                        st.setToY(0.9);
                        st.play();
                    });
                    button.setOnMouseReleased(e -> {
                        ScaleTransition st = new ScaleTransition(Duration.millis(100), button);
                        st.setToX(1.0);
                        st.setToY(1.0);
                        st.play();
                    });
                }
                if (node instanceof ToggleButton) {
                    ToggleButton toggleButton = (ToggleButton) node;
                    toggleButton.setOnMousePressed(e -> {
                        ScaleTransition st = new ScaleTransition(Duration.millis(100), toggleButton);
                        st.setToX(0.9);
                        st.setToY(0.9);
                        st.play();
                    });
                    toggleButton.setOnMouseReleased(e -> {
                        ScaleTransition st = new ScaleTransition(Duration.millis(100), toggleButton);
                        st.setToX(1.0);
                        st.setToY(1.0);
                        st.play();
                    });
                    toggleButton.selectedProperty().removeListener(this::handleToggleButtonSelection);
                    toggleButton.selectedProperty().addListener(this::handleToggleButtonSelection);
                    if (toggleButton.isSelected()) {
                        toggleButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                    } else {
                        toggleButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
                    }
                }
            }
        }

        playlistListView.setStyle("");
        playlistListView.getStyleClass().setAll("list-view");

        Node vScrollBar = playlistListView.lookup(".scroll-bar:vertical");
        if (vScrollBar != null) {
            vScrollBar.setStyle("");
            Node track = vScrollBar.lookup(".track");
            Node thumb = vScrollBar.lookup(".thumb");
            if (track != null) track.setStyle("");
            if (thumb != null) thumb.setStyle("");
        }
        playlistListView.refresh();
    }

    private void handleToggleButtonSelection(ObservableValue<? extends Boolean> obs, Boolean oldVal, Boolean newVal) {
        ToggleButton button = (ToggleButton) ((ReadOnlyProperty)obs).getBean();
        isShuffleMode = newVal;

        switch (currentThemeName) {
            case "frutigerAero":
                if (newVal) {
                    button.setStyle("-fx-background-color: linear-gradient(to top, rgba(144,238,144,0.7), rgba(0,128,0,0.9)); -fx-background-radius: 8px; -fx-border-radius: 8px; -fx-border-color: rgba(255,255,255,0.8); -fx-border-width: 1px; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0.0, 0, 0);");
                } else {
                    button.setStyle("-fx-background-color: linear-gradient(to top, rgba(255,99,71,0.7), rgba(255,0,0,0.9)); -fx-background-radius: 8px; -fx-border-radius: 8px; -fx-border-color: rgba(255,255,255,0.8); -fx-border-width: 1px; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0.0, 0, 0);");
                }
                break;
            case "halfLife":
                if (newVal) {
                    button.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: #000000; -fx-font-family: 'Consolas', monospace; -fx-font-weight: bold; -fx-effect: innershadow(gaussian, rgba(0,0,0,0.4), 5, 0.0, 0, 0); -fx-border-color: #666666; -fx-border-width: 1px;");
                } else {
                    button.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #ff9900; -fx-font-family: 'Consolas', monospace; -fx-font-weight: bold; -fx-effect: innershadow(gaussian, rgba(0,0,0,0.4), 5, 0.0, 0, 0); -fx-border-color: #666666; -fx-border-width: 1px;");
                }
                break;
            default:
                if (newVal) {
                    button.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                } else {
                    button.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
                }
                break;
        }
    }


    private HBox createNowPlayingBox() {
        HBox nowPlayingBox = new HBox();
        nowPlayingBox.getStyleClass().add("now-playing-box");
        nowPlayingBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        nowPlayingBox.setSpacing(10);

        nowPlayingHeaderLabel = new Label("Now Playing:");
        nowPlayingHeaderLabel.getStyleClass().add("now-playing-label");

        nowPlayingLabel = new Label("No track loaded");
        nowPlayingLabel.getStyleClass().add("track-info-label");
        nowPlayingLabel.setEllipsisString("...");
        nowPlayingLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nowPlayingLabel, Priority.ALWAYS);

        nowPlayingBox.getChildren().addAll(nowPlayingHeaderLabel, nowPlayingLabel);
        return nowPlayingBox;
    }

    private HBox createControlsBox() {
        HBox controls = new HBox();
        controls.getStyleClass().add("controls-box");

        Button playButton = new Button("▶");
        playButton.getStyleClass().add("control-button");
        Button pauseButton = new Button("❚❚");
        pauseButton.getStyleClass().add("control-button");
        Button stopButton = new Button("◼");
        stopButton.getStyleClass().add("control-button");
        Button prevButton = new Button("⏮");
        prevButton.getStyleClass().add("control-button");
        Button nextButton = new Button("⏭");
        nextButton.getStyleClass().add("control-button");
        ToggleButton shuffleButton = new ToggleButton("🔀 Shuffle");
        shuffleButton.getStyleClass().add("shuffle-button");

        seekLabel = new Label("00:00:00");
        seekLabel.getStyleClass().add("seek-label");
        seekSlider = new Slider(0, 1, 0);
        seekSlider.setPrefWidth(150);
        seekSlider.getStyleClass().add("slider");

        Label volumeLabel = new Label("Vol:");
        volumeLabel.getStyleClass().add("volume-label");
        volumeSlider = new Slider(0, 1, 0.4);
        volumeSlider.setPrefWidth(80);
        volumeSlider.getStyleClass().add("slider");

        Button cycleVisualizerButton = new Button("🔄 Visualizer");
        cycleVisualizerButton.getStyleClass().add("control-button");


        controls.getChildren().addAll(
                playButton, pauseButton, stopButton, prevButton, nextButton,
                shuffleButton,
                seekLabel, seekSlider,
                volumeLabel, volumeSlider,
                cycleVisualizerButton
        );

        Button[] animatedButtons = {playButton, pauseButton, stopButton, prevButton, nextButton, cycleVisualizerButton};
        for (Button button : animatedButtons) {
            button.setOnMousePressed(e -> {
                ScaleTransition st = new ScaleTransition(Duration.millis(100), button);
                st.setToX(0.9);
                st.setToY(0.9);
                st.play();
            });
            button.setOnMouseReleased(e -> {
                ScaleTransition st = new ScaleTransition(Duration.millis(100), button);
                st.setToX(1.0);
                st.setToY(1.0);
                st.play();
            });
        }

        shuffleButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");

        shuffleButton.setOnMousePressed(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(100), shuffleButton);
            st.setToX(0.9);
            st.setToY(0.9);
            st.play();
        });
        shuffleButton.setOnMouseReleased(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(100), shuffleButton);
            st.setToX(1.0);
            st.setToY(1.0);
            st.play();
        });


        playButton.setOnAction(_ -> {
            if (mediaPlayer != null) {
                mediaPlayer.play();
            } else if (!audioFiles.isEmpty()) {
                load(audioFiles.get(currentFileIndex).getAbsolutePath());
                mediaPlayer.play();
                updatePlaylistSelection();
            }
        });

        pauseButton.setOnAction(_ -> {
            if (mediaPlayer != null) mediaPlayer.pause();
        });

        stopButton.setOnAction(_ -> {
            if (mediaPlayer != null) {
                playbackPositions.put(audioFiles.get(currentFileIndex).getAbsolutePath(), mediaPlayer.getCurrentTime().toMillis());
                mediaPlayer.stop();
                mediaPlayer.seek(Duration.ZERO);
                seekLabel.setText("00:00:00");
                seekSlider.setValue(0);
            }
        });

        shuffleButton.selectedProperty().addListener(this::handleToggleButtonSelection);


        nextButton.setOnAction(_ -> {
            if (!audioFiles.isEmpty()) {
                if (mediaPlayer != null) {
                    playbackPositions.put(audioFiles.get(currentFileIndex).getAbsolutePath(), mediaPlayer.getCurrentTime().toMillis());
                }
                if (isShuffleMode) {
                    int nextIndex;
                    do {
                        nextIndex = new Random().nextInt(audioFiles.size());
                    } while (audioFiles.size() > 1 && nextIndex == currentFileIndex);
                    currentFileIndex = nextIndex;
                } else {
                    currentFileIndex = (currentFileIndex + 1) % audioFiles.size();
                }
                load(audioFiles.get(currentFileIndex).getAbsolutePath());
                mediaPlayer.play();
                updatePlaylistSelection();
            }
        });

        prevButton.setOnAction(_ -> {
            if (!audioFiles.isEmpty()) {
                if (mediaPlayer != null) {
                    playbackPositions.put(audioFiles.get(currentFileIndex).getAbsolutePath(), mediaPlayer.getCurrentTime().toMillis());
                }
                if (isShuffleMode) {
                    int prevIndex;
                    do {
                        prevIndex = new Random().nextInt(audioFiles.size());
                    } while (audioFiles.size() > 1 && prevIndex == currentFileIndex);
                    currentFileIndex = prevIndex;
                } else {
                    currentFileIndex = (currentFileIndex - 1 + audioFiles.size()) % audioFiles.size();
                }
                load(audioFiles.get(currentFileIndex).getAbsolutePath());
                mediaPlayer.play();
                updatePlaylistSelection();
            }
        });

        seekSlider.setOnMouseReleased(_ -> {
            if (mediaPlayer != null && mediaPlayer.getStatus() != MediaPlayer.Status.STOPPED) {
                double newTime = seekSlider.getValue() * mediaPlayer.getTotalDuration().toSeconds();
                mediaPlayer.seek(Duration.seconds(newTime));
            }
        });

        volumeSlider.valueProperty().addListener((_, _, newValue) -> {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(newValue.doubleValue());
            }
            volumeLabel.setText("Vol: " + String.format("%.0f%%", newValue.doubleValue() * 100));
        });

        cycleVisualizerButton.setOnAction(_ -> {
            int nextIndex = (currentVisualizerType.ordinal() + 1) % VisualizerType.values().length;
            currentVisualizerType = VisualizerType.values()[nextIndex];
            System.out.println("Switched Visualizer to: " + currentVisualizerType);
            //fuck this shit in particular, fuckass code
        });

        return controls;
    }

    private ListView<String> createPlaylistListView() {
        playlistListView = new ListView<>();
        playlistListView.getStyleClass().add("list-view");

        playlistListView.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    getStyleClass().removeAll("list-cell-selected", "list-cell-even", "list-cell-odd");
                    setStyle(null);
                } else {
                    String fileName = new File(item).getName();
                    setText(fileName.replaceFirst("[.][^.]+$", ""));

                    getStyleClass().removeAll("list-cell-selected", "list-cell-even", "list-cell-odd");
                    setStyle(null);

                    switch (currentThemeName) {
                        case "blackAndWhite":
                            if (getIndex() % 2 == 0) {
                                setStyle("-fx-background-color: #f0f0f0; -fx-text-fill: #000000;");
                            } else {
                                setStyle("-fx-background-color: #ffffff; -fx-text-fill: #000000;");
                            }
                            if (isSelected()) {
                                setStyle("-fx-background-color: #000000; -fx-text-fill: #ffffff;");
                            }
                            break;
                        case "oceanBreeze":
                            if (getIndex() % 2 == 0) {
                                setStyle("-fx-background-color: #34495e; -fx-text-fill: #ecf0f1;");
                            } else {
                                setStyle("-fx-background-color: #2c3e50; -fx-text-fill: #ecf0f1;");
                            }
                            if (isSelected()) {
                                setStyle("-fx-background-color: #2ecc71; -fx-text-fill: #000000;");
                            }
                            break;
                        case "fierySunset":
                            if (getIndex() % 2 == 0) {
                                setStyle("-fx-background-color: #4a2c2a; -fx-text-fill: #ecf0f1;");
                            } else {
                                setStyle("-fx-background-color: #34222e; -fx-text-fill: #ecf0f1;");
                            }
                            if (isSelected()) {
                                setStyle("-fx-background-color: #f39c12; -fx-text-fill: #000000;");
                            }
                            break;
                        case "mysticForest":
                            if (getIndex() % 2 == 0) {
                                setStyle("-fx-background-color: #2e4034; -fx-text-fill: #ecf0f1;");
                            } else {
                                setStyle("-fx-background-color: #1c2b20; -fx-text-fill: #ecf0f1;");
                            }
                            if (isSelected()) {
                                setStyle("-fx-background-color: #27ae60; -fx-text-fill: #000000;");
                            }
                            break;
                        case "frutigerAero":
                            setStyle("-fx-background-color: transparent; -fx-text-fill: #333333; -fx-font-weight: normal;");
                            if (isSelected()) {
                                setStyle("-fx-background-color: rgba(0,119,194,0.3); -fx-text-fill: #000000; -fx-font-weight: bold; -fx-background-radius: 5px;");
                            } else if (getIndex() % 2 == 0) {
                                setStyle("-fx-background-color: rgba(255,255,255,0.2);");
                            } else {
                                setStyle("-fx-background-color: rgba(255,255,255,0.1);");
                            }
                            break;
                        case "halfLife":
                            setStyle("-fx-background-color: transparent; -fx-text-fill: #cccccc; -fx-font-family: 'Consolas', monospace;");
                            if (isSelected()) {
                                setStyle("-fx-background-color: #ff9900; -fx-text-fill: #000000; -fx-font-weight: bold; -fx-font-family: 'Consolas', monospace;");
                            } else if (getIndex() % 2 == 0) {
                                setStyle("-fx-background-color: #222222;");
                            } else {
                                setStyle("-fx-background-color: #1a1a1a;");
                            }
                            setStyle(getStyle() + "-fx-border-color: #444444; -fx-border-width: 0 0 1px 0;");
                            break;
                        default:
                            if (getIndex() % 2 == 0) {
                                getStyleClass().add("list-cell-even");
                            } else {
                                getStyleClass().add("list-cell-odd");
                            }
                            if (isSelected()) {
                                getStyleClass().add("list-cell-selected");
                            }
                            break;
                            //FUCK STYLE SHEETS I FUCKING HATE STYLESHEETS
                    }
                }
            }
        });

        playlistListView.getSelectionModel().selectedIndexProperty().addListener((obs, oldIndex, newIndex) -> {
            if (newIndex != null && newIndex.intValue() >= 0 && newIndex.intValue() < audioFiles.size() && newIndex.intValue() != currentFileIndex) {
                currentFileIndex = newIndex.intValue();
                load(audioFiles.get(currentFileIndex).getAbsolutePath());
                mediaPlayer.play();
                updatePlaylistSelection();
            } else if (newIndex != null && newIndex.intValue() == currentFileIndex) {
                if (mediaPlayer != null && mediaPlayer.getStatus() == MediaPlayer.Status.PAUSED) {
                    mediaPlayer.play();
                } else if (mediaPlayer != null && mediaPlayer.getStatus() == MediaPlayer.Status.STOPPED) {
                    mediaPlayer.seek(Duration.ZERO);
                    mediaPlayer.play();
                }
                updatePlaylistSelection();
            }
        });

        return playlistListView;
    }

    private void loadAudioFilesFromDisk() {
        try (BufferedReader br = new BufferedReader(new FileReader("Audios.txt"))) {
            String line;
            while ((line = br.readLine()) != null) {
                File file = new File(line);
                if (file.exists()) {
                    audioFiles.add(file);
                    // this was smart, good job me!
                }
            }
        } catch (IOException ex) {
            System.err.println("Error reading Audios.txt: " + ex.getMessage());
        }
    }

    private void updatePlaylistView() {
        playlistListView.getItems().clear();
        for (File file : audioFiles) {
            playlistListView.getItems().add(file.getAbsolutePath());
        }
        updatePlaylistSelection();
    }

    private void updatePlaylistSelection() {
        if (!audioFiles.isEmpty() && currentFileIndex >= 0 && currentFileIndex < audioFiles.size()) {
            playlistListView.getSelectionModel().select(currentFileIndex);
            playlistListView.scrollTo(currentFileIndex);
            nowPlayingLabel.setText("Now Playing: " + audioFiles.get(currentFileIndex).getName().replaceFirst("[.][^.]+$", ""));
        } else {
            playlistListView.getSelectionModel().clearSelection();
            nowPlayingLabel.setText("No track loaded");
        }
        playlistListView.refresh();
    }

    private void drawWarpDrive() {
        if (!visualizerStage.isShowing()) {
            return;
            // wow that was soooooo complicated
        }

        double width = visualizerCanvas.getWidth();
        double height = visualizerCanvas.getHeight();

        gc.clearRect(0, 0, width, height);

        switch (currentThemeName) {
            case "halfLife":
                gc.setFill(Color.web("#1a1a1a"));
                break;
            case "frutigerAero":
                gc.setFill(Color.web("#e0f7fa"));
                break;
            default:
                gc.setFill(Color.BLACK);
                break;
        }
        gc.fillRect(0, 0, width, height);

        for (Star star : stars) {
            star.draw(gc);
        }
    }

    private void appendFileToAudiosTxt(String filePath) {
        Set<String> existingPaths = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new FileReader("Audios.txt"))) {
            String line;
            while ((line = br.readLine()) != null) existingPaths.add(line);
        } catch (IOException ignored) {
        }
        if (!existingPaths.contains(filePath)) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter("Audios.txt", true))) {
                bw.write(filePath);
                bw.newLine();
            } catch (IOException ex) {
                System.err.println("Write error to Audios.txt: " + ex.getMessage());
            }
        }
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        System.out.println("Application stopping. Releasing resources.");
        // well yea id hope so?
        if (mediaPlayer != null) {
            if (currentFileIndex >= 0 && currentFileIndex < audioFiles.size()) {
                playbackPositions.put(audioFiles.get(currentFileIndex).getAbsolutePath(), mediaPlayer.getCurrentTime().toMillis());
            }
            mediaPlayer.dispose();
            mediaPlayer = null;
        }
        if (backgroundAnimationTimeline != null) {
            backgroundAnimationTimeline.stop();
            backgroundAnimationTimeline = null;
        }
        if (visualizerStage != null && visualizerStage.isShowing()) {
            visualizerStage.close();
        }
    }

    public static void main(String[] args) {
        launch();
    }
}