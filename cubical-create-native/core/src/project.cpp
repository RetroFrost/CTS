#include "cubical/project.hpp"
#include <sstream>

namespace cubical {
std::string summary(const Project& project) {
    std::ostringstream out;
    out << project.name << " • " << project.cards.size() << " cards • "
        << project.width << 'x' << project.height << " @ " << project.fps << " FPS";
    return out.str();
}
}
