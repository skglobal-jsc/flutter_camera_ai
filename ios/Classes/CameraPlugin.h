#import <Flutter/Flutter.h>

@interface CameraPlugin : NSObject <FlutterPlugin>
+ (CameraPlugin*) getCurrentInstance;
@property(nonatomic, copy) bool (^isStable)(UIImage* image);
@end
