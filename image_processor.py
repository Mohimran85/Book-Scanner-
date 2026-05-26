import cv2
import numpy as np

class ImageProcessor:
    def __init__(self):
        pass

    def enhance_page(self, page_img):
        """
        Enhances the page image for OCR by applying adaptive thresholding,
        noise removal, and basic deskewing if necessary.
        """
        # Convert to grayscale
        gray = cv2.cvtColor(page_img, cv2.COLOR_BGR2GRAY)
        
        # Remove shadows and normalize brightness by dividing by a blurred version
        dilated_img = cv2.dilate(gray, np.ones((7,7), np.uint8)) 
        bg_img = cv2.medianBlur(dilated_img, 21)
        diff_img = 255 - cv2.absdiff(gray, bg_img)
        norm_img = cv2.normalize(diff_img, None, alpha=0, beta=255, norm_type=cv2.NORM_MINMAX, dtype=cv2.CV_8UC1)
        
        # Apply adaptive thresholding
        thresh = cv2.adaptiveThreshold(norm_img, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, 
                                       cv2.THRESH_BINARY, 11, 2)
                                       
        # Deskewing (simple version)
        coords = np.column_stack(np.where(thresh > 0))
        if len(coords) > 0:
            angle = cv2.minAreaRect(coords)[-1]
            if angle < -45:
                angle = -(90 + angle)
            else:
                angle = -angle
                
            # If the angle is significant, rotate
            if abs(angle) > 0.5:
                (h, w) = thresh.shape[:2]
                center = (w // 2, h // 2)
                M = cv2.getRotationMatrix2D(center, angle, 1.0)
                thresh = cv2.warpAffine(thresh, M, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
                
        return thresh
