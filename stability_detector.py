import cv2
import numpy as np
import time

class StabilityDetector:
    def __init__(self, threshold=5.0, history_length=5):
        """
        threshold: The maximum mean absolute difference between frames to be considered stable.
        history_length: How many consecutive frames must be stable to trigger a capture.
        """
        self.threshold = threshold
        self.history_length = history_length
        self.prev_gray = None
        self.stable_frames_count = 0
        self.has_moved = True  # Ensures we must detect motion before capturing the first page
        self.last_capture_time = 0
        self.cooldown_seconds = 2.0 # Prevent multiple captures of the same page

    def is_stable(self, frame):
        """
        Checks if the current frame is stable compared to the previous frame.
        """
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        gray = cv2.GaussianBlur(gray, (21, 21), 0) # Blur to reduce noise sensitivity

        is_currently_stable = False
        motion_score = 0.0

        if self.prev_gray is not None:
            # Compute absolute difference between current frame and previous frame
            frame_diff = cv2.absdiff(self.prev_gray, gray)
            motion_score = np.mean(frame_diff)
            
            if motion_score < self.threshold:
                self.stable_frames_count += 1
                if self.stable_frames_count >= self.history_length:
                    is_currently_stable = True
            else:
                self.stable_frames_count = 0
                self.has_moved = True # Detected motion, ready for next page

        self.prev_gray = gray
        return is_currently_stable, motion_score

    def should_capture(self, frame):
        """
        Determines if a capture should be triggered based on stability and cooldown.
        """
        stable, score = self.is_stable(frame)
        current_time = time.time()
        
        if stable and self.has_moved and (current_time - self.last_capture_time > self.cooldown_seconds):
            self.last_capture_time = current_time
            self.stable_frames_count = 0 # Reset after capture
            self.has_moved = False # Require motion before next capture
            return True, score
            
        return False, score
