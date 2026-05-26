import cv2
import time
import os
import argparse
from book_detector import BookDetector
from stability_detector import StabilityDetector
from image_processor import ImageProcessor

def main():
    parser = argparse.ArgumentParser(description="Smart Book Scanner Prototype")
    parser.add_argument('--video', type=str, help='Path to a sample video file to use for testing. Defaults to webcam (0).')
    args = parser.parse_args()

    # Initialize modules
    book_detector = BookDetector()
    stability_detector = StabilityDetector(threshold=3.0, history_length=10)
    image_processor = ImageProcessor()
    
    # Create output directory
    output_dir = "scanned_pages"
    os.makedirs(output_dir, exist_ok=True)
    
    # Open camera or video file
    video_source = args.video if args.video else 0
    cap = cv2.VideoCapture(video_source)
    
    if not cap.isOpened():
        print(f"Error: Could not open source {video_source}.")
        return

    # Calculate delay for normal video playback speed
    if args.video:
        fps = cap.get(cv2.CAP_PROP_FPS)
        wait_time = int(1000 / fps) if fps > 0 else 30
    else:
        wait_time = 1

    print("Scanner Started. Press 'q' to quit.")
    
    page_count = 1
    
    while True:
        ret, frame = cap.read()
        if not ret:
            print("Failed to grab frame")
            break
            
        # 1. Detect Book and Center Fold
        book_contour, debug_frame = book_detector.detect_book_boundary(frame)
        center_x, debug_frame = book_detector.detect_center_fold(debug_frame, book_contour)
        
        # 2. Check Stability
        stable, score = stability_detector.is_stable(frame)
        
        # Determine if we should capture
        should_capture, _ = stability_detector.should_capture(frame)
        
        # Visual feedback
        status_text = "STABLE - Capturing..." if should_capture else ("STABLE" if stable else "MOTION DETECTED")
        color = (0, 255, 0) if stable else (0, 0, 255)
        
        cv2.putText(debug_frame, f"Status: {status_text}", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 1, color, 2)
        cv2.putText(debug_frame, f"Motion Score: {score:.2f}", (10, 70), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)
        
        cv2.imshow("Book Scanner Preview", debug_frame)
        
        # 3. Capture and Process
        if should_capture:
            print(f"Captured Page Set {page_count}!")
            
            # Flash effect on screen (optional, difficult in basic OpenCV but we can print)
            left_page, right_page = book_detector.split_pages(frame, center_x)
            
            # Enhance
            left_enhanced = image_processor.enhance_page(left_page)
            right_enhanced = image_processor.enhance_page(right_page)
            
            # Save
            left_path = os.path.join(output_dir, f"page_{page_count:03d}_L.png")
            right_path = os.path.join(output_dir, f"page_{page_count:03d}_R.png")
            
            cv2.imwrite(left_path, left_enhanced)
            cv2.imwrite(right_path, right_enhanced)
            
            print(f"Saved: {left_path}, {right_path}")
            page_count += 1
            
            # Preview captured
            cv2.imshow("Last Captured - Left", cv2.resize(left_enhanced, (300, 400)))
            cv2.imshow("Last Captured - Right", cv2.resize(right_enhanced, (300, 400)))

        # Handle keypress
        key = cv2.waitKey(wait_time) & 0xFF
        if key == ord('q'):
            break

    cap.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
