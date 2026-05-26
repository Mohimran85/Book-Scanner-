import cv2
import numpy as np

class BookDetector:
    def __init__(self):
        pass

    def detect_book_boundary(self, frame):
        """
        Detects the boundary of the book in the given frame.
        For prototyping, we assume the largest rectangular contour in the lower/middle section.
        """
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)
        edged = cv2.Canny(blurred, 50, 150)
        
        # Dilate to close gaps
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
        dilated = cv2.dilate(edged, kernel, iterations=1)

        contours, _ = cv2.findContours(dilated.copy(), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        
        if not contours:
            return None, frame

        # Find the largest contour by area
        largest_contour = max(contours, key=cv2.contourArea)
        
        # Approximate the contour to a polygon
        epsilon = 0.02 * cv2.arcLength(largest_contour, True)
        approx = cv2.approxPolyDP(largest_contour, epsilon, True)

        # Draw the contour on a copy for visualization
        debug_frame = frame.copy()
        cv2.drawContours(debug_frame, [approx], -1, (0, 255, 0), 2)
        
        return approx, debug_frame

    def detect_center_fold(self, frame, book_contour=None):
        """
        Detects the center fold of the book. 
        A simple approach: assuming the camera is centered, the fold is roughly in the middle,
        or we can look for a vertical dark line / depression.
        """
        height, width = frame.shape[:2]
        
        # Simple prototype: split down the middle of the frame or the detected book bounding box
        if book_contour is not None and len(book_contour) > 0:
            x, y, w, h = cv2.boundingRect(book_contour)
            center_x = x + w // 2
        else:
            center_x = width // 2
            
        # Draw the center line for visualization
        debug_frame = frame.copy()
        cv2.line(debug_frame, (center_x, 0), (center_x, height), (0, 0, 255), 2)
        
        return center_x, debug_frame

    def split_pages(self, frame, center_x):
        """
        Splits the frame into left and right pages based on the center_x.
        """
        height, width = frame.shape[:2]
        left_page = frame[0:height, 0:center_x]
        right_page = frame[0:height, center_x:width]
        return left_page, right_page
