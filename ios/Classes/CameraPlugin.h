#import <Flutter/Flutter.h>

@protocol FLTCamDelegate <NSObject>
-(void)cameraTorchDidChange:(BOOL)isActivate;
-(void)brightnessDidChange:(int)brightnessLevel;
@end

@interface CameraPlugin : NSObject <FlutterPlugin, FLTCamDelegate>
+ (CameraPlugin*) getCurrentInstance;
@property(nonatomic, copy) bool (^isStable)(UIImage* image);
@end
