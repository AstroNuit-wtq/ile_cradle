#include <cassert>
#include <cmath>
#include <iostream>

#include "../app/src/main/cpp/heading_filter.h"

int main() {
    CircularHeadingFilter filter;
    assert(filter.update(359.0f, 0.02f));
    const float initial = filter.value();
    assert(filter.update(1.0f, 0.02f));
    assert(std::fabs(filter.value() - initial) < 3.0f || std::fabs(filter.value() - initial + 360.0f) < 3.0f);

    const float beforeSpike = filter.value();
    const bool acceptedSpike = filter.update(180.0f, 0.01f);
    assert(!acceptedSpike);
    assert(std::fabs(filter.value() - beforeSpike) < 0.001f);

    for (int i = 0; i < 30; ++i) filter.update(2.0f, 0.02f);
    assert(filter.value() < 10.0f || filter.value() > 350.0f);

    filter.reset();
    assert(!filter.hasValue());
    assert(filter.update(-90.0f, 0.02f));
    assert(std::fabs(filter.value() - 270.0f) < 0.01f);

    std::cout << "Compass filter tests passed\n";
    return 0;
}
