package com.kschulz.aro.data.model

/** Origin of a blood-pressure reading. Drives the composite PK so a manual and an
 *  auto reading at the same instant do not collide. */
enum class BpSource { MANUAL, AUTO }
