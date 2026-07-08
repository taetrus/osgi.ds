package com.kk.pde.ds.app;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Calendar;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kk.pde.ds.api.IGreet;
import com.kk.pde.ds.chatbot.ChatFrame;
import com.kk.pde.ds.chatbot.ChatService;
import com.kk.pde.ds.chatbot.DarkTheme;

@Component
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private IGreet greet;
    private ChatService chatService;

    // Held so @Deactivate can dispose them; set/read across the EDT and the SCR
    // deactivation thread, hence volatile.
    private volatile ClockFrame clockFrame;
    private volatile ChatFrame chatFrame;

    @Reference
    public void setChatService(ChatService chatService) {
        log.info("App.setChatService()");
        this.chatService = chatService;
    }

    public App() {
        log.info("App.App()");
    }

    @Reference
    public void setApi(IGreet greet) {
        log.info("App.setApi()");
        this.greet = greet;
    }

    @Activate
    public void start() {
        log.info("App.start()");

        greet.greet();

        // Launch the UI — apply dark theme BEFORE creating any components
        SwingUtilities.invokeLater(() -> {
            DarkTheme.apply();
            clockFrame = new ClockFrame();
            clockFrame.setVisible(true);
            chatFrame = new ChatFrame(chatService);
            chatFrame.setVisible(true);
        });
    }

    /**
     * Dispose the UI when the component/bundle stops. Without this, stopping the app
     * bundle leaves both windows open with the clock's 1s Timer still firing, and a
     * restart would spawn duplicate windows. Disposal runs on the EDT.
     */
    @Deactivate
    public void stop() {
        log.info("App.stop()");
        final ClockFrame clock = clockFrame;
        final ChatFrame chat = chatFrame;
        clockFrame = null;
        chatFrame = null;
        SwingUtilities.invokeLater(() -> {
            if (clock != null) {
                clock.dispose();
            }
            if (chat != null) {
                chat.dispose();
            }
        });
    }

    /**
     * Analog Clock Panel
     */
    static class ClockPanel extends JPanel {
        private static final int CLOCK_RADIUS = 80;
        private static final int CENTER_X = CLOCK_RADIUS + 20;
        private static final int CENTER_Y = CLOCK_RADIUS + 20;

        private Timer timer;
        private Calendar calendar;

        public ClockPanel() {
            setBackground(new Color(30, 30, 40));
            setPreferredSize(new Dimension(CLOCK_RADIUS * 2 + 40, CLOCK_RADIUS * 2 + 40));
            
            calendar = Calendar.getInstance();
            
            timer = new Timer(1000, new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    calendar = Calendar.getInstance();
                    repaint();
                }
            });
            timer.start();
        }

        /** Stop the repaint timer so it doesn't keep firing after the window is gone. */
        void stopTimer() {
            if (timer != null) {
                timer.stop();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            
            // Enable anti-aliasing
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            int hours = calendar.get(Calendar.HOUR);
            int minutes = calendar.get(Calendar.MINUTE);
            int seconds = calendar.get(Calendar.SECOND);
            int millis = calendar.get(Calendar.MILLISECOND);

            // Draw clock face
            g2d.setColor(new Color(50, 50, 65));
            g2d.fillOval(CENTER_X - CLOCK_RADIUS, CENTER_Y - CLOCK_RADIUS, 
                        CLOCK_RADIUS * 2, CLOCK_RADIUS * 2);
            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(3));
            g2d.drawOval(CENTER_X - CLOCK_RADIUS, CENTER_Y - CLOCK_RADIUS, 
                        CLOCK_RADIUS * 2, CLOCK_RADIUS * 2);

            // Draw hour markers
            g2d.setStroke(new BasicStroke(2));
            for (int i = 0; i < 12; i++) {
                Point p1 = getPointOnCircle(i * 30, CLOCK_RADIUS - 10);
                Point p2 = getPointOnCircle(i * 30, CLOCK_RADIUS - 5);
                g2d.setColor(i % 3 == 0 ? Color.ORANGE : Color.LIGHT_GRAY);
                g2d.drawLine(p1.x, p1.y, p2.x, p2.y);
            }

            // Calculate angles (subtract 90 degrees because 0 is at 3 o'clock)
            double secondAngle = Math.toRadians(90 - (seconds + millis / 1000.0) * 6);
            double minuteAngle = Math.toRadians(90 - (minutes + seconds / 60.0) * 6);
            double hourAngle = Math.toRadians(90 - (hours + minutes / 60.0) * 30);

            // Draw hour hand
            g2d.setColor(new Color(255, 150, 50));
            g2d.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Point hourEnd = getPointOnCircle(hourAngle, CLOCK_RADIUS * 0.5);
            g2d.drawLine(CENTER_X, CENTER_Y, hourEnd.x, hourEnd.y);

            // Draw minute hand
            g2d.setColor(Color.CYAN);
            g2d.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Point minuteEnd = getPointOnCircle(minuteAngle, CLOCK_RADIUS * 0.7);
            g2d.drawLine(CENTER_X, CENTER_Y, minuteEnd.x, minuteEnd.y);

            // Draw second hand
            g2d.setColor(Color.RED);
            g2d.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Point secondEnd = getPointOnCircle(secondAngle, CLOCK_RADIUS * 0.9);
            g2d.drawLine(CENTER_X, CENTER_Y, secondEnd.x, secondEnd.y);

            // Draw center dot
            g2d.setColor(Color.ORANGE);
            g2d.fillOval(CENTER_X - 5, CENTER_Y - 5, 10, 10);
        }

        private Point getPointOnCircle(double angle, double radius) {
            int x = CENTER_X + (int) (radius * Math.cos(angle));
            int y = CENTER_Y - (int) (radius * Math.sin(angle));
            return new Point(x, y);
        }
    }

    /**
     * Clock Frame
     */
    static class ClockFrame extends JFrame {
        private final ClockPanel clockPanel;

        public ClockFrame() {
            setTitle("🕐 OSGi Clock");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setResizable(false);

            clockPanel = new ClockPanel();
            add(clockPanel);

            pack();
            setLocationRelativeTo(null);
        }

        @Override
        public void dispose() {
            // Stop the timer whether the window is closed by the user or disposed
            // programmatically on bundle stop.
            clockPanel.stopTimer();
            super.dispose();
        }
    }
}
