2048
====

This is an example project for Neuos SDK based on Jerry Jiang's port of 2048 game to Android, all credit for game goes to him and to Gabriele Cirulli.

[Original code](https://github.com/tpcstld/2048) (github.com)

[Download on Google Play](https://play.google.com/store/apps/details?id=com.tpcstld.twozerogame) (play.google.com)

The project has been adapted to include realtime feedback from the [Neuos-SDK](https://github.com/arctop/Neuos-SDK) 

We have added an activity that handles connection and setup. Once that is complete the game's original main activity launches, binds to Neuos, and displays real time values calculated by the SDK. It is meant as a very simple use case example to demonstarte incorprating the SDK with an app.
